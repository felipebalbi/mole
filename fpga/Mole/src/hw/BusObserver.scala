package mole

import spinal.core._

/** Two-FF bus observer + edge detectors.
  *
  * Shared scaffolding for controller-role flag handling and target-role bit
  * pacing. Constructed inside [[BitCycleEngineCore]] from the registered sample
  * lines on the pads, so every consumer reads the same metastability-resolved
  * values. Returns an [[Area]] (not a [[Component]]) so it does not add a
  * hierarchy level and Verilator-printed signal names stay readable.
  *
  * Init values: sync shifts come up all-ones (recessive idle) so the first two
  * post-reset cycles do not synthesise a spurious falling-edge pulse. The
  * `*SampledPrev` regs follow the same convention --- real-world buses come up
  * recessive.
  *
  * Edge pulses are one fabric cycle wide on the sample-domain transition that
  * produced them.
  *
  * @param sdaRaw
  *   The unsynchronized SDA `read` from the pad.
  * @param sclRaw
  *   The unsynchronized SCL `read` from the pad.
  */
case class BusObserver(sdaRaw: Bool, sclRaw: Bool) extends Area {

  val sdaSyncShift = Reg(Bits(2 bits)) init (B"11")
  val sclSyncShift = Reg(Bits(2 bits)) init (B"11")
  sdaSyncShift := sdaSyncShift(0) ## sdaRaw
  sclSyncShift := sclSyncShift(0) ## sclRaw

  val sdaSampled: Bool = sdaSyncShift(1)
  val sclSampled: Bool = sclSyncShift(1)

  val sdaSampledPrev: Bool = RegNext(sdaSampled) init (True)
  val sclSampledPrev: Bool = RegNext(sclSampled) init (True)

  val sdaFalling: Bool = sdaSampledPrev && !sdaSampled
  val sdaRising: Bool = !sdaSampledPrev && sdaSampled
  val sclFalling: Bool = sclSampledPrev && !sclSampled
  val sclRising: Bool = !sclSampledPrev && sclSampled

  val startEdge: Bool = sdaFalling && sclSampled
  val stopEdge: Bool = sdaRising && sclSampled
}
