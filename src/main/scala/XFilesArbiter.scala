package dana

import Chisel._

case object NumCores extends Field[Int]

abstract trait XFilesParameters extends UsesParameters {
  val numCores = params(NumCores)
}

abstract class XFilesModule extends DanaModule with XFilesParameters
abstract class XFilesBundle extends DanaBundle with XFilesParameters

class XFilesDanaInterface extends XFilesBundle {
  val control = new TTableControlInterface
  val peTable = (new PETransactionTableInterface).flip
}

class XFilesInterface extends XFilesBundle {
  val core = Vec.fill(numCores){ new RoCCInterface }
  val dana = new XFilesDanaInterface
}

class XFilesArbiter extends XFilesModule {
  val io = new XFilesInterface

  // Module instatiation
  val tTable = Module(new TransactionTable)
  val asidRegs = Vec.fill(numCores){ Module(new AsidUnit).io }
  val coreQueue = Vec.fill(numCores){ Module(new Queue(new RoCCCommand, 4)).io }

  // Default values
  for (i <- 0 until numCores) {
    io.core(i).resp.valid := Bool(false)
    io.core(i).resp.bits.rd := UInt(0)
    io.core(i).resp.bits.data := UInt(0)
  }
  tTable.io.arbiter.rocc.resp.ready := Bool(true)

  // Non-supervisory requests from cores are fed into a round robin
  // arbiter.
  val coreArbiter = Module(new RRArbiter(new RoCCCommand,
    numCores))
  for (i <- 0 until numCores) {
    // Core to core-specific queue connections. The ASID/TID are setup
    // here if needed.
    coreQueue(i).enq.valid := io.core(i).cmd.valid & !io.core(i).s
    // If this is a write and is new, then we need to add the TID
    // specified by the ASID unit. Otherwise, we only need to stamp
    // the ASID as the core provided the TID. We also need to respond
    // to the specific core that initiated this request telling it
    // what the TID is.
    when (io.core(i).cmd.bits.inst.funct(0) && io.core(i).cmd.bits.inst.funct(1)) {
      coreQueue(i).enq.bits := io.core(i).cmd.bits
      coreQueue(i).enq.bits.rs1 :=
        io.core(i).cmd.bits.rs1(feedbackWidth - 1, 0) ##
        asidRegs(i).asid ##
        asidRegs(i).tid
      // Respond to the core with the TID
      // io.core(i).resp.valid := Bool(false)
      // io.core(i).resp.bits.rd := io.core(i).cmd.bits.inst.rd
      // io.core(i).resp.bits.data := asidRegs(i).tid << UInt(elementWidth)
      // tTable.io.arbiter.rocc.resp.ready := Bool(false)
    } .otherwise {
      coreQueue(i).enq.bits := io.core(i).cmd.bits
      coreQueue(i).enq.bits.rs1 :=
        asidRegs(i).asid ##
        io.core(i).cmd.bits.rs1(tidWidth - 1, 0)
    }
    io.core(i).cmd.ready := coreQueue(i).enq.ready
    // Queue to RRArbiter connections
    coreQueue(i).deq.ready := coreArbiter.io.in(i).ready
    coreArbiter.io.in(i).valid := coreQueue(i).deq.valid
    coreArbiter.io.in(i).bits := coreQueue(i).deq.bits
    // coreArbiter.io.in(i).bits.rs1 :=
    //   Cat(io.core(i).cmd.bits.rs1(feedbackWidth + tidWidth - 1, tidWidth),
    //     asidRegs(i).asid,
    //     io.core(i).cmd.bits.rs1(tidWidth - 1, 0))
    io.core(i).cmd.ready := coreArbiter.io.in(i).ready
    // Inbound reqeusts are also fed into the ASID registers
    asidRegs(i).core.cmd.valid := io.core(i).cmd.valid
    asidRegs(i).core.cmd.bits := io.core(i).cmd.bits
    asidRegs(i).core.s := io.core(i).s
  }

  // When we see a valid response from the Transaction Table, we let
  // it through. [TODO] This needs to include the assignment of output
  // values to the correct core.
  when (tTable.io.arbiter.rocc.resp.valid) {
    io.core(tTable.io.arbiter.indexOut).resp.valid := tTable.io.arbiter.rocc.resp.valid
    io.core(tTable.io.arbiter.indexOut).resp.bits := tTable.io.arbiter.rocc.resp.bits
  }

  // Interface connections
  coreArbiter.io.out <> tTable.io.arbiter.rocc.cmd
  tTable.io.arbiter.coreIdx := coreArbiter.io.chosen
  io.dana.control <> tTable.io.control
  io.dana.peTable <> tTable.io.peTable

  // Assertions
}