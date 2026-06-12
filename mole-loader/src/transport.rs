//! Serial-port transport: ship a `.mole.bin` frame to a Mole engine
//! and drain the resulting ring back.
//!
//! # Wire contract recap
//!
//! - UART, default 2 Mbaud (per AGENTS preference; fallback 1 Mbaud
//!   at 16× via FALLBACK_BAUD), 8N1, **mandatory hardware RTS/CTS
//!   flow control** (see `fpga/Mole/WIRE_FORMAT.md` and
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
//! # Stale-buffer defence at open time
//!
//! [`Transport::open`] calls
//! [`SerialPort::clear`]`(`[`ClearBuffer::Input`]`)` immediately
//! after configuring the port. This discards any RX bytes the kernel
//! was holding in the tty buffer from a *previous* process that
//! talked to the same device. Without that clear, a fresh
//! `Transport::open` followed by `drain_ring(8192)` could satisfy
//! the read entirely from stale kernel-buffered bytes and never
//! actually talk to the engine --- producing a "fake clean run"
//! that looks identical to a real one in the decoder. See
//! F-HOST-009 in the design notes.
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

use serialport::{ClearBuffer, DataBits, FlowControl, Parity, SerialPort, StopBits};

use crate::error::{TransportError, TransportPhase};

/// Default baud rate the Mole engine speaks: 2 Mbaud at 8×
/// oversampling (see `fpga/Mole/WIRE_FORMAT.md`). Re-exported from
/// [`mole_abi::DEFAULT_BAUD`] so host and engine share one source.
/// When 2 Mbaud is not achievable on a particular host adapter,
/// use [`FALLBACK_BAUD`] instead.
pub const DEFAULT_BAUD: u32 = mole_abi::DEFAULT_BAUD;

/// Fallback baud rate when [`DEFAULT_BAUD`] (2 Mbaud) is not viable.
///
/// 1 Mbaud at 16× oversampling. Re-exported from
/// [`mole_abi::FALLBACK_BAUD`] so the CLI and library share one
/// source.
pub const FALLBACK_BAUD: u32 = mole_abi::FALLBACK_BAUD;

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
pub const DEFAULT_RING_BYTES: usize = mole_abi::RESULT_RING_BYTE_COUNT;

/// Maximum I/O chunk size for incremental writes / reads. Small
/// enough to give `indicatif` a smooth animation; large enough that
/// per-syscall overhead is negligible at 2 Mbaud.
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

        // Discard any bytes the kernel was holding in the RX buffer
        // before we opened the port.
        //
        // The serial port driver buffers RX bytes independently of
        // process lifetimes: a previous loader-cli invocation that
        // drained 8 KiB from the engine and then exited leaves those
        // bytes sitting in the kernel's tty layer for the *next*
        // process to open the same device. Without this clear, the
        // next `drain_ring()` call satisfies itself from that stale
        // buffer in microseconds and returns a byte-perfect replay
        // of the previous program's drain --- a "fake clean run" that
        // looks identical to a real one in the decoder. F-HOST-009
        // (silently-stale-drain hazard); reproduced on the bench
        // with blinky returning byte-identical-to-tmp108 ring data
        // because the FPGA was in an infinite loop and never streamed
        // new bytes.
        //
        // We intentionally clear only the INPUT buffer (not Output or
        // All): the kernel TX buffer may legitimately contain bytes
        // we wrote in a previous call (the caller may chain
        // `Transport::open().with_timeout(...).send_frame(...)` and
        // we don't want to drop in-flight writes). The TX side is
        // also far less prone to this hazard because every successful
        // `send_frame()` ends in `flush()` which blocks until the
        // OS hands all bytes to the driver.
        port.clear(ClearBuffer::Input)
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

        // Manual byte-counting loop per chunk, mirroring the shape
        // of `drain_ring` below. We deliberately do NOT use
        // `write_all` here: `write_all` swallows the partial-write
        // count before returning `WouldBlock`, so a benign-retry
        // `continue` would re-issue the whole chunk and duplicate
        // the prefix the kernel already accepted on the wire.
        // Some USB-CDC drivers do exactly that (accept N bytes,
        // then surface `WouldBlock` for the rest of the chunk),
        // which is how F-HOST-008 manifested.
        for chunk in frame.chunks(IO_CHUNK_BYTES) {
            let mut sent = 0;
            while sent < chunk.len() {
                match self.port.write(&chunk[sent..]) {
                    Ok(0) => {
                        // Per stdlib `io::Write::write_all` semantics:
                        // a zero-byte successful write means "no
                        // progress possible". On a serial port this
                        // is typically the link being unplugged
                        // mid-transfer. Surface as `WriteZero` so it
                        // routes through the generic `Io` variant.
                        return Err(TransportError::Io {
                            phase: TransportPhase::Load,
                            source: std::io::Error::new(
                                ErrorKind::WriteZero,
                                "write returned 0 bytes (link may be disconnected)",
                            ),
                        });
                    }
                    Ok(n) => {
                        sent += n;
                        progress.tick(done + sent);
                    }
                    // Benign --- retry against the *remaining* slice
                    // on the next loop turn. `sent` does not advance,
                    // so we re-issue against `&chunk[sent..]` and no
                    // bytes are duplicated. `Interrupted` is a
                    // signal-delivered spurious return; `WouldBlock`
                    // shows up under some USB-CDC drivers that
                    // transiently drop into non-blocking mode. The
                    // per-write timeout configured on the port still
                    // bounds the total wait.
                    Err(e)
                        if e.kind() == ErrorKind::Interrupted
                            || e.kind() == ErrorKind::WouldBlock =>
                    {
                        continue;
                    }
                    Err(e) if e.kind() == ErrorKind::TimedOut => {
                        return Err(TransportError::Timeout {
                            phase: TransportPhase::Load,
                            after_bytes: done + sent,
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
            done += chunk.len();
            progress.tick(done);
        }
        // Make sure everything has actually left the OS buffer before
        // we hand control back --- the engine reads via DMA and we
        // do not want a half-frame still queued when the caller goes
        // to drain.
        //
        // A `TimedOut` here is almost always the engine's CTS being
        // stuck deasserted (HW flow-control stall): the host TX
        // buffer fills, `flush()` blocks waiting for the driver to
        // hand bytes off, and the per-op timeout fires. Surface
        // that as a structured `Timeout{Load}` rather than an
        // opaque `Io` so the caller can render the actual failure
        // mode --- it is the single most common bring-up symptom
        // and previously had the worst diagnostic.
        if let Err(e) = self.port.flush() {
            if e.kind() == ErrorKind::TimedOut {
                return Err(TransportError::Timeout {
                    phase: TransportPhase::Load,
                    after_bytes: frame.len(),
                    expected_bytes: frame.len(),
                });
            }
            return Err(TransportError::Io {
                phase: TransportPhase::Load,
                source: e,
            });
        }
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
                // Benign --- retry the read on the next loop turn.
                // `Interrupted`: a signal (SIGWINCH from a terminal
                // resize, SIGCHLD, etc.) was delivered while
                // `read()` was blocked; the kernel returns
                // spuriously rather than restarting the syscall.
                // `WouldBlock`: some USB-CDC drivers transiently
                // surface non-blocking semantics. Neither is a
                // transport failure; the per-read timeout
                // configured on the port still bounds the total
                // wait, and `done` is unchanged so we resume
                // exactly where we left off --- partial bytes are
                // preserved, which matters for `--dump-ring`
                // forensics on a real failure later in the drain.
                Err(e)
                    if e.kind() == ErrorKind::Interrupted || e.kind() == ErrorKind::WouldBlock =>
                {
                    continue;
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

    #[test]
    fn default_baud_matches_abi() {
        // Keep the assertion behind the constant so it is not a
        // brittle literal that needs updating when the ABI changes.
        assert_eq!(DEFAULT_BAUD, mole_abi::DEFAULT_BAUD);
    }

    #[test]
    fn fallback_baud_matches_abi() {
        assert_eq!(FALLBACK_BAUD, mole_abi::FALLBACK_BAUD);
    }
}
