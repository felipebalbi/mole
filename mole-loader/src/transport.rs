//! Serial-port transport: ship a `.mole.bin` frame to a Mole engine
//! and drain the resulting ring back.
//!
//! # Wire contract recap
//!
//! - UART, default 1 Mbaud, 8N1, **mandatory hardware RTS/CTS flow
//!   control** (see `fpga/Mole/WIRE_FORMAT.md` and
//!   `fpga/Mole/src/sim/MoleTopFlowControlSim.scala`).
//! - The host writes the frame bytes; the engine pulls them through
//!   its loader FSM, gated by CTS.
//! - On HALT the engine's drainer streams `resultRingByteCount`
//!   bytes back, gated by host RTS. The host reads exactly that
//!   many bytes.
//!
//! # Why we *don't* manually toggle RTS / read CTS
//!
//! The OS serial driver does that for us when [`FlowControl::Hardware`]
//! is configured: blocking writes back-pressure on CTS, blocking
//! reads pace on RTS. The platform-specific details (POSIX termios
//! vs Win32 DCB) live in the [`serialport`] crate. Failure to enable
//! hardware flow control is fatal --- the loader refuses to fall back
//! to software / no flow control because doing so is guaranteed to
//! drop bytes against the Mole's loader FSM.
//!
//! # Progress reporting
//!
//! This module emits zero progress bars itself. Callers that want a
//! progress bar pass a [`Progress`] callback into
//! [`Transport::send_frame`] / [`Transport::drain_ring`]. The CLI in
//! the sibling crate wires that up to `indicatif`; programmatic
//! callers can pass [`Progress::none`] and get total silence.

use std::io::{ErrorKind, Read, Write};
use std::time::Duration;

use serialport::{DataBits, FlowControl, Parity, SerialPort, StopBits};

use crate::error::{TransportError, TransportPhase};

/// Default baud rate the Mole engine speaks (see
/// `fpga/Mole/WIRE_FORMAT.md`).
pub const DEFAULT_BAUD: u32 = 1_000_000;

/// Default per-operation I/O timeout. Big enough that a slow program
/// run plus the ring drain can complete; small enough that a missing
/// HALT surfaces in a reasonable time. Override per-call via
/// [`Transport::with_timeout`].
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(30);

/// Default result-ring size in bytes (matches
/// `MoleConfig.resultRingByteCount` Verde default: 8192 bytes =
/// 4096 words). The drainer always streams exactly this many bytes
/// per HALT, so an under-sized request leaves the HALT word in the
/// kernel buffer and the next decode sees a record header at the
/// tail; an over-sized request will block the read waiting for
/// bytes the engine will never send. If you have rebuilt the
/// engine with a non-default `resultRingByteCount`, pass
/// `--ring-bytes` to match.
pub const DEFAULT_RING_BYTES: usize = 8192;

/// Maximum I/O chunk size for incremental writes / reads. Small
/// enough to give `indicatif` a smooth animation; large enough that
/// per-syscall overhead is negligible at 1 Mbaud.
const IO_CHUNK_BYTES: usize = 256;

/// Tick callback handed to [`Transport::send_frame`] /
/// [`Transport::drain_ring`].
///
/// `bytes_done` is the running total of bytes transferred so far in
/// the current call (not a delta). Implementors typically forward
/// that to an `indicatif::ProgressBar::set_position(...)`.
pub struct Progress<'a> {
    tick: Box<dyn FnMut(usize) + 'a>,
}

impl<'a> Progress<'a> {
    /// Build a [`Progress`] from a closure.
    pub fn new<F: FnMut(usize) + 'a>(f: F) -> Self {
        Self { tick: Box::new(f) }
    }

    /// No-op callback. Use when you do not want progress reporting.
    pub fn none() -> Self {
        Self::new(|_| {})
    }

    fn tick(&mut self, bytes_done: usize) {
        (self.tick)(bytes_done);
    }
}

/// Opened serial connection to a Mole engine.
///
/// Wraps a boxed [`SerialPort`] from the `serialport` crate together
/// with the path it was opened from (for error messages) and the
/// current I/O timeout. Use [`Transport::open`] to construct one.
pub struct Transport {
    port: Box<dyn SerialPort>,
    path: String,
    timeout: Duration,
}

impl Transport {
    /// Open a serial port at `path`, configure it for the Mole engine
    /// (8N1, hardware RTS/CTS, `baud` baud), and return the wrapped
    /// connection.
    ///
    /// Hardware flow control is mandatory; if the OS refuses to enable
    /// it, this returns [`TransportError::ConfigureFlowControl`]
    /// rather than continuing with software flow control.
    ///
    /// # Errors
    ///
    /// See [`TransportError`].
    pub fn open(path: &str, baud: u32) -> Result<Self, TransportError> {
        // Set flow control on the BUILDER (not post-open). On Windows
        // the serialport-rs `set_flow_control(Hardware)` post-open
        // path has historically not reliably reconfigured the DCB's
        // `fRtsControl` to `RTS_CONTROL_HANDSHAKE`, leaving RTS stuck
        // deasserted so the engine's drain bytes never reach the
        // host. Setting it at open time avoids that.
        let mut port = serialport::new(path, baud)
            .data_bits(DataBits::Eight)
            .parity(Parity::None)
            .stop_bits(StopBits::One)
            .flow_control(FlowControl::Hardware)
            .timeout(DEFAULT_TIMEOUT)
            .open()
            .map_err(|source| TransportError::OpenPort {
                path: path.to_string(),
                source,
            })?;

        // Re-assert it post-open too, both to confirm the platform
        // honours it and to keep the distinct error path for the
        // "OS refuses HW flow control" failure mode.
        port.set_flow_control(FlowControl::Hardware)
            .map_err(|source| TransportError::ConfigureFlowControl {
                path: path.to_string(),
                source,
            })?;

        // Re-set framing in case the platform did not honour the
        // builder defaults (e.g. some USB-CDC drivers).
        port.set_data_bits(DataBits::Eight)
            .and_then(|()| port.set_parity(Parity::None))
            .and_then(|()| port.set_stop_bits(StopBits::One))
            .map_err(|source| TransportError::Configure {
                path: path.to_string(),
                source,
            })?;

        Ok(Self {
            port,
            path: path.to_string(),
            timeout: DEFAULT_TIMEOUT,
        })
    }

    /// Override the per-call I/O timeout.
    ///
    /// Returns `&mut Self` so the call chains: `Transport::open(...)?
    /// .with_timeout(Duration::from_secs(60));`.
    ///
    /// # Errors
    ///
    /// Propagates the underlying [`serialport`] error if the driver
    /// refuses to update the timeout.
    pub fn with_timeout(&mut self, timeout: Duration) -> Result<&mut Self, TransportError> {
        self.port
            .set_timeout(timeout)
            .map_err(|source| TransportError::Configure {
                path: self.path.clone(),
                source,
            })?;
        self.timeout = timeout;
        Ok(self)
    }

    /// Path the transport was opened with. Useful for error messages
    /// in calling code.
    pub fn path(&self) -> &str {
        &self.path
    }

    /// Currently configured per-operation I/O timeout.
    pub fn timeout(&self) -> Duration {
        self.timeout
    }

    /// Write the full `frame` to the engine. Returns when all bytes
    /// have been accepted by the OS driver.
    ///
    /// `progress` is ticked once per chunk write with the running
    /// byte count. Pass [`Progress::none`] for silence.
    ///
    /// # Errors
    ///
    /// [`TransportError::Timeout`] if the configured timeout fires
    /// before all bytes are accepted (typically: engine CTS is stuck
    /// low). [`TransportError::Io`] for any other I/O failure.
    pub fn send_frame(
        &mut self,
        frame: &[u8],
        progress: &mut Progress<'_>,
    ) -> Result<(), TransportError> {
        let total = frame.len();
        let mut done = 0;
        progress.tick(done);

        for chunk in frame.chunks(IO_CHUNK_BYTES) {
            match self.port.write_all(chunk) {
                Ok(()) => {
                    done += chunk.len();
                    progress.tick(done);
                }
                Err(e) if e.kind() == ErrorKind::TimedOut => {
                    return Err(TransportError::Timeout {
                        phase: TransportPhase::Load,
                        after_bytes: done,
                        expected_bytes: total,
                    });
                }
                Err(source) => {
                    return Err(TransportError::Io {
                        phase: TransportPhase::Load,
                        source,
                    });
                }
            }
        }
        // Make sure everything has actually left the OS buffer before
        // we hand control back --- the engine reads via DMA and we
        // do not want a half-frame still queued when the caller goes
        // to drain.
        self.port.flush().map_err(|source| TransportError::Io {
            phase: TransportPhase::Load,
            source,
        })?;
        Ok(())
    }

    /// Read exactly `ring_bytes` bytes back from the engine. Returns
    /// the full buffer for handing to [`crate::ring::decode_ring`].
    ///
    /// `progress` is ticked once per chunk read with the running
    /// byte count.
    ///
    /// # Errors
    ///
    /// [`TransportError::Timeout`] if the configured timeout fires
    /// before all bytes are received. [`TransportError::Io`] for any
    /// other I/O failure.
    pub fn drain_ring(
        &mut self,
        ring_bytes: usize,
        progress: &mut Progress<'_>,
    ) -> Result<Vec<u8>, TransportError> {
        let mut buf = vec![0u8; ring_bytes];
        let mut done = 0;
        progress.tick(done);

        while done < ring_bytes {
            let want = (ring_bytes - done).min(IO_CHUNK_BYTES);
            match self.port.read(&mut buf[done..done + want]) {
                Ok(0) => {
                    // EOF on a serial port usually means the driver
                    // unplugged; surface as an I/O error rather than
                    // a silent under-read.
                    return Err(TransportError::Io {
                        phase: TransportPhase::Drain,
                        source: std::io::Error::new(
                            ErrorKind::UnexpectedEof,
                            format!("drain returned EOF after {done} of {ring_bytes} bytes"),
                        ),
                    });
                }
                Ok(n) => {
                    done += n;
                    progress.tick(done);
                }
                Err(e) if e.kind() == ErrorKind::TimedOut => {
                    return Err(TransportError::Timeout {
                        phase: TransportPhase::Drain,
                        after_bytes: done,
                        expected_bytes: ring_bytes,
                    });
                }
                Err(source) => {
                    return Err(TransportError::Io {
                        phase: TransportPhase::Drain,
                        source,
                    });
                }
            }
        }
        Ok(buf)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn progress_none_tick_is_a_no_op() {
        let mut p = Progress::none();
        // Ticking should not panic and the closure should accept
        // arbitrary counts.
        p.tick(0);
        p.tick(123);
        p.tick(usize::MAX);
    }

    #[test]
    fn progress_new_invokes_closure() {
        use std::cell::RefCell;
        let observed = RefCell::new(Vec::<usize>::new());
        let mut p = Progress::new(|n| observed.borrow_mut().push(n));
        p.tick(0);
        p.tick(64);
        p.tick(128);
        assert_eq!(*observed.borrow(), vec![0, 64, 128]);
    }
}
