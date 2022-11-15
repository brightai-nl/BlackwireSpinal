package corundum

import spinal.core._
import spinal.sim._
import spinal.core.sim._

import scala.util.Random

object AxisWireguardKeyLookupSim {
  def main(args: Array[String]) {
    val dataWidth = 128

    val maxDataValue = scala.math.pow(2, dataWidth).intValue - 1
    val keepWidth = dataWidth/8
    SimConfig
    .withFstWave
    .doSim(new AxisWireguardKeyLookup(dataWidth, true)){dut =>

      SimTimeout(100000 * 10)

      var maxFrameWords = 16
      var maxPacketSizeBytes = (maxFrameWords + 2) * keepWidth + keepWidth - 1

      dut.io.sink.valid #= false

      //Fork a process to generate the reset and the clock on the dut
      dut.clockDomain.forkStimulus(period = 10)

      var modelState = 0

      //StreamFragmentGenerator(event: x, packetData: y, dataType: CorundumFrame)
      val dw = dataWidth / 4

      //Create a new thread
      val myNewThread = fork {
        var not_last_seen = false
        var in_packet_continuation = false
        var first_beat = false
        var packet_length = 0
        var remaining = 0
        while (true) {
          // wait for beat on DUT source
          dut.clockDomain.waitSamplingWhere(dut.io.source.valid.toBoolean & dut.io.source.ready.toBoolean)
          first_beat = !in_packet_continuation
          if (first_beat) {
            packet_length = dut.io.source_length.toInt
            remaining = dut.io.source_length.toInt
          }
          //var clean_data = dut.io.source.payload.fragment.toBigInt
          //if (remaining < dataWidth / 8) {
          //  var mask = BigInt(0)
          //  for (byte <- 0 until remaining) {
          //    mask <<= 8
          //    mask = (mask | 0xFF)
          //  }
          //  clean_data = mask
          //}
          
          printf(s"SOURCE == 0x%0${dw}X %02d/%02d %s%s RECEIVER=0x%08X COUNTER=0x%08X\n",
            dut.io.source.payload.fragment.toBigInt,
            packet_length - remaining, packet_length,
            if (first_beat) "*" else s" ",
            if (dut.io.source.payload.last.toBoolean) "L" else s" ",
            dut.io.receiver.toBigInt, dut.io.counter.toBigInt)
          // during any valid beat, the receiver and counter should
          // be from the header bytes [8:4] and [12:9] resp.
          if (dut.io.source.valid.toBoolean & first_beat) {
            assert((dut.io.receiver.toBigInt & 0x0F0F0F0F) == 0x08070605)
          }
          if (dut.io.source.valid.toBoolean) {
            assert((dut.io.counter.toBigInt & 0x0F0F0F0F) == 0x0C0B0A09)
          }
          in_packet_continuation = !dut.io.source.payload.last.toBoolean
          remaining = if (remaining >= dataWidth/8) remaining - dataWidth/8 else 0
        }
      }

      var data0 = BigInt(0)
      var last0 = false
      var valid0 = false
      var tkeep0 = 0
      var pause = false

      dut.clockDomain.waitSampling()

      var packet_idx = 128 / 8
      // iterate over all frames to generate
      while (packet_idx < (128 * 3 + 63) / 8) {
        var packet_length = packet_idx //1 + Random.nextInt(if (packet_idx > 3400) keepWidth else maxPacketSizeBytes)
        //val packet_length = packet_idx match {
        //case (packet_idx > 3400): 1 + Random.nextInt(keepWidth)
        //case (packet_idx > 4800): 0
        //case _: maxPacketSizeBytes
        //}
        assert(packet_length <= maxPacketSizeBytes)
        var remaining = packet_length
        var byte_counter = 0

        var clock_counter = 0
        // iterate over frame content
        while (remaining > 0) {
          val tkeep_len = if (remaining >= keepWidth) keepWidth else remaining;
          // simulate source not always valid
          valid0 = (Random.nextInt(8) > 2) | (packet_idx > 3000)
          valid0 &= !pause
          //valid0 = true

          //println(clock_counter + s" pause " + pause + s", valid " + valid0)
          clock_counter += 1

          if (pause) pause ^= (Random.nextInt(16) >= 15)
          else if (!pause) pause ^= (Random.nextInt(128) >= 127)
          // limit to single beat activities
          //pause = false

          assert(tkeep_len <= keepWidth)
          tkeep0 = 0
          data0 = 0
          last0 = false
          if (valid0) {
            last0 = (remaining <= keepWidth)
            for (i <- 0 until tkeep_len) {
              tkeep0 = (tkeep0 << 1) | 1
            }
            // fill data with bytes
            // high nibble is packet index
            // low nibble is byte index (within packet)
            for (i <- 0 until tkeep_len) {
              data0 = (data0 << 8) | ((packet_idx % 16) * 0x10) | ((byte_counter + tkeep_len - i) % 16)
            }
          }

          dut.io.sink.valid #= valid0
          dut.io.sink.last #= last0
          dut.io.sink.payload.fragment #= data0
          dut.io.sink_length #= packet_length

          dut.io.source.ready #= (Random.nextInt(8) > 1) | (packet_idx > 4000)
          dut.io.source.ready #= true

          // Wait for falling edge on the clock
          dut.clockDomain.waitFallingEdge()

          printf(s"SINK == 0x%0${dw}X, %s %s %s remaining=%d\n",
            dut.io.sink.payload.fragment.toBigInt,
            if (dut.io.sink.ready.toBoolean) "READY" else "",
            if (dut.io.sink.valid.toBoolean) "VALID" else "",
            if (dut.io.sink.ready.toBoolean & dut.io.sink.valid.toBoolean) "FIRE" else "",
            remaining)

          if (dut.io.sink.ready.toBoolean & dut.io.sink.valid.toBoolean) {
            byte_counter += tkeep_len

            remaining -= tkeep_len
            if (remaining <= 0) {
              packet_idx += 1
            }
          }

          // Wait for rising edge on the clock
          dut.clockDomain.waitRisingEdge()

        }
        // after each packet, introduce delay for now
        dut.io.sink.valid #= false
        //dut.clockDomain.waitRisingEdge(16)
      }
      dut.io.sink.valid #= false
      while (dut.io.source.valid.toBoolean) {
          // Wait a rising edge on the clock
          dut.clockDomain.waitRisingEdge()
      }
      dut.clockDomain.waitRisingEdge(8)
    }
  }
}

object AxisWireguardType4Sim {
  def main(args: Array[String]) {
    val dataWidth = 512
    val maxDataValue = scala.math.pow(2, dataWidth).intValue - 1
    val keepWidth = dataWidth/8
    SimConfig
    .withFstWave
    .doSim(AxisWireguardType4()){dut =>

      dut.io.sink.valid #= false

      //Fork a process to generate the reset and the clock on the dut
      dut.clockDomain.forkStimulus(period = 10)

      var data0 = 0

      var last0 = false
      var valid0 = false
      var tkeep0 = BigInt(0)
      var pause = false

      dut.clockDomain.waitSampling()

      // 000  4c 61 64 69 65 73 20 61 6e 64 20 47 65 6e 74 6c  Ladies and Gentl
      // 016  65 6d 65 6e 20 6f 66 20 74 68 65 20 63 6c 61 73  emen of the clas
      // 032  73 20 6f 66 20 27 39 39 3a 20 49 66 20 49 20 63  s of '99: If I c
      // 048  6f 75 6c 64 20 6f 66 66 65 72 20 79 6f 75 20 6f  ould offer you o
      // 064  6e 6c 79 20 6f 6e 65 20 74 69 70 20 66 6f 72 20  nly one tip for
      // 080  74 68 65 20 66 75 74 75 72 65 2c 20 73 75 6e 73  the future, suns
      // 096  63 72 65 65 6e 20 77 6f 75 6c 64 20 62 65 20 69  creen would be i
      // 112  74 2e                                            t.

      // BigInt("4c616469657320616e642047656e746c656d656e206f662074686520636c617373206f66202739393a204966204920636f756c64206f6666657220796f75206f", 16) // Ladies and Gentlemen of the class of '99: If I could offer you o
      // BigInt("6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73637265656e20776f756c642062652069742e0000000000000000000000000000", 16) // nly one tip for the future, sunscreen would be it.

      val plaintext = Vector(
        BigInt("04 00 00 00 11 22 33 44 c1 c2 c3 c4 c5 c6 c7 c8 4c 61 64 69 65 73 20 61 6e 64 20 47 65 6e 74 6c 65 6d 65 6e 20 6f 66 20 74 68 65 20 63 6c 61 73 73 20 6f 66 20 27 39 39 3a 20 49 66 20 49 20 63".split(" ").reverse.mkString(""), 16),
        BigInt("6f 75 6c 64 20 6f 66 66 65 72 20 79 6f 75 20 6f 6e 6c 79 20 6f 6e 65 20 74 69 70 20 66 6f 72 20 74 68 65 20 66 75 74 75 72 65 2c 20 73 75 6e 73 63 72 65 65 6e 20 77 6f 75 6c 64 20 62 65 20 69".split(" ").reverse.mkString(""), 16),
        BigInt("74 2e 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00".split(" ").reverse.mkString(""), 16)
      )

      //  BigInt("0400000011223344c1c2c3c4c5c6c7c84c616469657320616e642047656e746c656d656e206f662074686520636c617373206f66202739393a20496620492063", 16),
      //  BigInt("6f756c64206f6666657220796f75206f6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73637265656e20776f756c642062652069", 16),
      //  BigInt("742e0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", 16)

      var packet_length = 16 + 114 // bytes
      var remaining = packet_length

      var word_index = 0
      // iterate over frame content
      while (remaining > 0) {
        printf("remaining = %d\n", remaining)
        val tkeep_len = if (remaining >= keepWidth) keepWidth else remaining;
        printf("tkeep_len = %d\n", tkeep_len)
        valid0 = (Random.nextInt(8) > 2)
        valid0 &= !pause
        if (pause) pause ^= (Random.nextInt(16) >= 15)
        if (!pause) pause ^= (Random.nextInt(128) >= 127)

        assert(tkeep_len <= keepWidth)
        tkeep0 = 0
        data0 = 0
        if (valid0) {
          last0 = (remaining <= keepWidth)
          for (i <- 0 until tkeep_len) {
            tkeep0 = (tkeep0 << 1) | 1
          }
        }

        dut.io.sink.valid #= valid0
        dut.io.sink.payload.tdata #= plaintext(word_index)
        dut.io.sink.last #= last0
        dut.io.sink.payload.tkeep #= tkeep0

        dut.io.source.ready #= (Random.nextInt(8) > 1)

        // Wait a rising edge on the clock
        dut.clockDomain.waitRisingEdge()

        if (dut.io.sink.ready.toBoolean & dut.io.sink.valid.toBoolean) {
          remaining -= tkeep_len
          word_index += 1
        }
      }
      dut.io.sink.valid #= false
      dut.io.source.ready #= true
      while (dut.io.source.valid.toBoolean) {
          // Wait a rising edge on the clock
          dut.clockDomain.waitRisingEdge()
      }
      dut.clockDomain.waitRisingEdge(8)
      dut.clockDomain.waitRisingEdge(8)
    }
  }
}