package corundum

import spinal.core._
import spinal.lib._

import spinal.lib.bus.misc._
import spinal.lib.bus.amba4.axi._

import scala.math._

// companion object
object AxisDownSizer {
}

/* Down size the AXIS data width with a factor of 2 or 4
 *
 * sink accepts AXIS frames (Ethernet packet)
 * sink_length is the input packet length in bytes, this packet arrives on the sink
 *
 * source is the output packet (Ethernet payload)
 * source_length is the output packet (Ethernet payload)
 */

case class AxisDownSizer(dataWidthIn : Int, dataWidthOut: Int) extends Component {
  /* upsizing is not supported yet */
  require(dataWidthIn > dataWidthOut, "dataWidthOut must be an integer division of dataWidthIn")
  /* non-integer width factors are not supported */
  require(dataWidthIn % dataWidthOut == 0, "dataWidthOut must be an integer division of dataWidthIn")

  val io = new Bundle {
    // I/O is only the Corundum Frame tdata payload
    val sink = slave Stream(Fragment(Bits(dataWidthIn bits)))
    val source = master Stream(Fragment(Bits(dataWidthOut bits)))
    // sink_length is given in bytes
    val sink_length = in UInt(12 bits)
    val source_length = out UInt(12 bits)
  }

  // translateWith() for Stream(Fragment())
  // (before this we needed to work-around this, see AxisUpSizer.scala commented out code)
  implicit class FragmentPimper[T <: Data](v: Fragment[T]) {
    def ~~[T2 <: Data](trans: T => T2) = {
      val that = trans(v.fragment)
      val res = Fragment(cloneOf(that))
      res.fragment := trans(v.fragment)
      res.last := v.last
      res
    }
  }
  // x is sink, but adds the sink_length as stream payload
  // such that both sink and sink_length are skid buffered
  val x = Stream(Fragment(Bits(dataWidthIn + 12 bits)))
  x << io.sink.~~(_.~~(io.sink_length.asBits ## _)).s2mPipe().m2sPipe()
   
  // y is input stream with original payload, but after the skid buffer
  val y = Stream(Fragment(Bits(dataWidthIn bits)))
  y << x.~~(_.~~(_.resize(dataWidthIn)))
  val y_length = (x.payload.fragment >> dataWidthIn).asUInt

  val z = Stream(Fragment(Bits(dataWidthOut bits)))

  // calculate number of output beats based on input length
  val out_beat_last = (y_length - 1) / (dataWidthOut / 8)
  val out_beat_num = Reg(U(0, 12 bits)) init(0)

  // current output beat in z?
  when (z.fire) {
    // last output beat for this input frame?
    when (out_beat_num === out_beat_last) {
      out_beat_num := 0
    } otherwise {
      out_beat_num := out_beat_num + 1
    }
  }
  val factor = dataWidthIn / dataWidthOut
  // when the input can take the next beat
  val next_input = ((out_beat_num % factor) === (factor - 1)) |
    (out_beat_num === out_beat_last)

  val counter = out_beat_num.resize(log2Up(factor))
  z.valid := y.valid
  //endianness match {
  //  case `LITTLE` =>
    z.fragment.assignFromBits(y.fragment.asBits.resize(dataWidthIn).subdivideIn(factor slices).read(counter))
  //  case `BIG`    => output.fragment.assignFromBits(y.fragment.asBits.resize(paddedInputWidth).subdivideIn(factor slices).reverse.read(counter))
  // }
  z.last := y.last && (out_beat_num === out_beat_last)
  y.ready := z.ready && next_input

  // register outputs
  io.source <-< z
  io.source_length := RegNext(y_length)

  // Execute the function renameAxiIO after the creation of the component
  addPrePopTask(() => CorundumFrame.renameAxiIO(io))
}

//Generate the AxisDownSizer's Verilog
object AxisDownSizerVerilog {
  def main(args: Array[String]) {
    SpinalVerilog(new AxisDownSizer(512, 128))
  }
}

//Generate the AxisDownSizer's VHDL
object AxisDownSizerVhdl {
  def main(args: Array[String]) {
    SpinalVhdl(new AxisDownSizer(512, 128))
  }
}