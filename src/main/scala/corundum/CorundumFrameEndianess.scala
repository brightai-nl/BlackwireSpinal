package corundum

import spinal.core._
import spinal.lib._

import spinal.lib.bus.misc._
import spinal.lib.bus.amba4.axi._

import scala.math._

// companion object
object CorundumFrameEndianess {
  // generate VHDL and Verilog
  def main(args: Array[String]) {
    SpinalVerilog(new CorundumFrameEndianess(512))
    SpinalVhdl(new CorundumFrameEndianess(512))
  }
}

case class CorundumFrameEndianess(dataWidth : Int) extends Component {
  val io = new Bundle {
    val sink = slave Stream Fragment(CorundumFrame(dataWidth))
    val source = master Stream Fragment(CorundumFrame(dataWidth))
  }
  val num_bytes = dataWidth / 8

  val x = Stream Fragment(CorundumFrame(dataWidth))
  x << io.sink

  // reverse endianess in tdata and tkeep
  when (True) {
    x.payload.fragment.tdata.assignFromBits(io.sink.payload.fragment.tdata.asBits.subdivideIn(num_bytes slices).reverse.asBits())
    x.payload.fragment.tkeep.assignFromBits(io.sink.payload.fragment.tkeep.asBits.subdivideIn(num_bytes slices).reverse.asBits())
  }
  io.source << x

  // Execute the function renameAxiIO after the creation of the component
  addPrePopTask(() => CorundumFrame.renameAxiIO(io))
}