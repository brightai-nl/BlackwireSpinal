package corundum

import spinal.core._
import spinal.lib._

import spinal.lib.bus.misc._
import spinal.lib.bus.amba4.axi._

import scala.math._

// companion object
object AxisWireguardKeyLookup {
}

/* Extract and remove the Wireguard Type 4 header, output receiver for key lookup
 * Delay output to match key lookup latency of two clock cycles.
 *
 * sink accepts AXIS frames (Ethernet packet)
 * sink_length is the input packet length in bytes, this packet arrives on the sink
 *
 * source is the output packet (Ethernet payload)
 * source_length is the output packet (Ethernet payload)
 */

case class AxisWireguardKeyLookup(dataWidth : Int, has_internal_test_lut : Boolean = false) extends Component {
  require(dataWidth == 128, "Assuming Wireguard Type 4 header is one word")
  val io = new Bundle {
    // I/O is only the Corundum Frame tdata payload
    val sink = slave Stream(Fragment(Bits(dataWidth bits)))
    val source = master Stream(Fragment(Bits(dataWidth bits)))
    // sink_length is given in bytes
    val sink_length = in UInt(12 bits)
    val source_length = out UInt(12 bits)
    // outgoing lookup index for the key
    val receiver = out UInt(32 bits)
    val counter = out UInt(64 bits)
    // key will have 2 cycles latency relative to receiver
    val key_in = (!has_internal_test_lut) generate (in Bits(256 bits))
    val key_out = out Bits(256 bits)
  }

  // register Wireguard Type 4 header fields
  val receiver = RegNextWhen(io.sink.payload(4 * 8, 32 bits), io.sink.isFirst)
  val counter = RegNextWhen (io.sink.payload(8 * 8, 64 bits), io.sink.isFirst)

  // drive external key LUT, expect key_in valid after two clock cycles
  io.receiver := receiver.asUInt
  io.counter := counter.asUInt

  val sink_is_first = io.sink.isFirst

  // remove 128 bits Wireguard Type 4 header and 128 bits tag from output length
  val unpadded_length_out = RegNextWhen(io.sink_length - 128/8 - 128/8, io.sink.isFirst)
  // round up to next 16 bytes (should we always do this? -- Ethernet MTU?)
  val padded16_length_out = RegNext(((unpadded_length_out + 15) >> 4) << 4)
  // during testing, do not pad, to verify correct propagation delay in simulator
  //val padded16_length_out = RegNext(unpadded_length_out)

  val x = Stream(Fragment(Bits(dataWidth bits)))

  // two cycles latency
  x <-< io.sink.stage()

  val y = Stream(Fragment(Bits(dataWidth bits)))
  // pipeline x into y, thereby replacing first beat
  // modify 128 bits Wireguard Type 4 header on output by clearing valid
  // register to achieve one pipeline stage
  // replace reserved field with payload length
  val header_payload = x.payload(127 downto 32) ## padded16_length_out.resize(24).asBits ## x.payload(7 downto 0)
  val y_payload = Bits(dataWidth bits)
  y_payload.assignFromBits(Mux(x.isFirst, header_payload, x.payload))

  val delete_header = x.isFirst & False

  // we keep the header; it carries the counter and the payload length in the reserved field
  y.valid := RegNextWhen(x.valid /*& !delete_header*/, x.ready) init(False)
  y.payload := RegNextWhen(y_payload, x.ready)
  y.last := RegNextWhen(x.last, x.ready)
  x.ready := y.ready

  io.source << y

  io.source_length := RegNext(padded16_length_out)

  val gen_external_keylut = (!has_internal_test_lut) generate new Area {
    // optional hardware here
    io.key_out := io.key_in
  }
  val gen_internal_keylut = (has_internal_test_lut) generate new Area {
    val keys_num = 256
    val lut = LookupTable(256/*bits*/, keys_num)

    // key from RFC -note all entries are the same, so every receiver value will return this key in the LUT
    lut.mem.initBigInt(Seq.fill(keys_num)(BigInt("80 81 82 83 84 85 86 87 88 89 8a 8b 8c 8d 8e 8f 90 91 92 93 94 95 96 97 98 99 9a 9b 9c 9d 9e 9f".split(" ").reverse.mkString(""), 16)))

    //lut.mem.initBigInt(Seq.range(0xA000, 0xA000 + keys_num))
    lut.io.portA.en := True
    lut.io.portA.wr := False
    lut.io.portA.wrData := 0
    lut.io.portA.addr := receiver.resize(log2Up(keys_num)).asUInt

    io.key_out := lut.io.portA.rdData

    lut.io.portB.clk := ClockDomain.current.readClockWire
    lut.io.portB.rst := False
    lut.io.portB.en := True
    lut.io.portB.wr := False
    lut.io.portB.wrData := 0
    lut.io.portB.addr := 0
  }
  val source_is_first = io.source.isFirst

  // Rename SpinalHDL library defaults to AXI naming convention
  private def renameIO(): Unit = {
    io.flatten.foreach(bt => {
      if(bt.getName().contains("_payload_fragment")) bt.setName(bt.getName().replace("_payload_fragment", "_tdata"))
      if(bt.getName().contains("_payload_last")) bt.setName(bt.getName().replace("_payload_last", "_tlast"))
      if(bt.getName().contains("_payload"))  bt.setName(bt.getName().replace("_payload",  ""))
      if(bt.getName().contains("_fragment")) bt.setName(bt.getName().replace("_fragment", ""))
      if(bt.getName().contains("_valid"))    bt.setName(bt.getName().replace("_valid",    "_tvalid"))
      if(bt.getName().contains("_ready"))    bt.setName(bt.getName().replace("_ready",    "_tready"))
      if(bt.getName().contains("_last"))     bt.setName(bt.getName().replace("_last",     "_tlast"))
      if(bt.getName().contains("reset"))     bt.setName(bt.getName().replace("reset",     "rst"))
    })
  }
  // Remove io_ prefix
  noIoPrefix()

  // Execute the function renameIO after the creation of the component
  addPrePopTask(() => renameIO())
}

//Generate the AxisWireguardKeyLookup's Verilog
object AxisWireguardKeyLookupVerilog {
  def main(args: Array[String]) {
    SpinalVerilog(new AxisWireguardKeyLookup(128, has_internal_test_lut = true))
  }
}

//Generate the AxisWireguardKeyLookup's VHDL
object AxisWireguardKeyLookupVhdl {
  def main(args: Array[String]) {
    SpinalVhdl(new AxisWireguardKeyLookup(128, has_internal_test_lut = true))
  }
}

// composition of the RX data flow towards ChaCha20-Poly1305
case class AxisWireguardType4() extends Component {
 final val corundumDataWidth = 512
 final val cryptoDataWidth = 128
 final val maxPacketLength = 1534
  // 1534 rounded up 2048/(512/8) == 32

  val io = new Bundle {
    // I/O is only the Corundum Frame tdata payload
    val sink = slave Stream Fragment(CorundumFrame(corundumDataWidth))
    val source = master Stream(Fragment(Bits(cryptoDataWidth bits)))
    val key = out Bits(256 bits)
  }
  val stash = CorundumFrameStash(corundumDataWidth, 32)
  stash.io.sink << io.sink

  // extract only TDATA into fragment
  val x = Stream(Fragment(Bits(corundumDataWidth bits)))
  val fff = Fragment(Bits(corundumDataWidth bits))
  fff.last := stash.io.source.payload.last
  fff.fragment := stash.io.source.payload.fragment.tdata
  x << stash.io.source.translateWith(fff)

  val downsizer = AxisWidthAdapter(corundumDataWidth, cryptoDataWidth)
  downsizer.io.sink << x
  downsizer.io.sink_length := stash.io.length
  val type4 = AxisWireguardKeyLookup(cryptoDataWidth, has_internal_test_lut = false)
  type4.io.sink << downsizer.io.source
  type4.io.sink_length := downsizer.io.source_length

  io.source << type4.io.source
  io.key := type4.io.key_out

  val keys_num = 256
  val lut = LookupTable(256/*bits*/, keys_num)
  lut.mem.initBigInt(Seq.fill(keys_num)(BigInt("80 81 82 83 84 85 86 87 88 89 8a 8b 8c 8d 8e 8f 90 91 92 93 94 95 96 97 98 99 9a 9b 9c 9d 9e 9f".split(" ").reverse.mkString(""), 16)))

  lut.io.portA.en := True
  lut.io.portA.wr := False
  lut.io.portA.wrData := 0
  lut.io.portA.addr := type4.io.receiver.resize(log2Up(keys_num))
  type4.io.key_in := lut.io.portA.rdData

  lut.io.portB.clk := ClockDomain.current.readClockWire
  lut.io.portB.rst := False
  lut.io.portB.en := True
  lut.io.portB.wr := False
  lut.io.portB.wrData := 0
  lut.io.portB.addr := 0

    // Rename SpinalHDL library defaults to AXI naming convention
  private def renameIO(): Unit = {
    io.flatten.foreach(bt => {
      if(bt.getName().contains("_payload_fragment")) bt.setName(bt.getName().replace("_payload_fragment", "_tdata"))
      if(bt.getName().contains("_payload_last")) bt.setName(bt.getName().replace("_payload_last", "_tlast"))
      if(bt.getName().contains("_payload"))  bt.setName(bt.getName().replace("_payload",  ""))
      if(bt.getName().contains("_fragment")) bt.setName(bt.getName().replace("_fragment", ""))
      if(bt.getName().contains("_valid"))    bt.setName(bt.getName().replace("_valid",    "_tvalid"))
      if(bt.getName().contains("_ready"))    bt.setName(bt.getName().replace("_ready",    "_tready"))
      if(bt.getName().contains("_last"))     bt.setName(bt.getName().replace("_last",     "_tlast"))
      if(bt.getName().contains("_tdata_"))   bt.setName(bt.getName().replace("_tdata_",     "_"))
      if(bt.getName().contains("reset"))     bt.setName(bt.getName().replace("reset",     "rst"))
    })
  }
  // Remove io_ prefix
  noIoPrefix()

  // Execute the function renameIO after the creation of the component
  addPrePopTask(() => renameIO())
}

//Generate the AxisWireguardType4's VHDL
object AxisWireguardType4Vhdl {
  def main(args: Array[String]) {
    SpinalVhdl(new AxisWireguardType4())
  }
}