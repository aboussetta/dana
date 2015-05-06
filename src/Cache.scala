package dana

import Chisel._

class CacheState extends DanaBundle()() {
  // nnsim-hdl equivalent:
  //   cache_types::cache_config_entry_struct
  val valid = Reg(Bool(), init = Bool(false))
  val notifyFlag = Reg(Bool())
  val fetch = Reg(Bool())
  val notifyIndex = Reg(UInt(width = log2Up(transactionTableNumEntries)))
  val notifyMask = Reg(UInt(width = transactionTableNumEntries))
  val nnid = Reg(UInt(width = nnidWidth))
  val inUseCount = Reg(UInt(width = log2Up(transactionTableNumEntries)))
}

class CacheMemInterface extends DanaBundle()() {
  // Outbound request. nnsim-hdl equivalent:
  //   cache_types::cache2mem_struct
  val req = Decoupled(new DanaBundle()() {
    val nnid = UInt(width = nnidWidth)
    // [TODO] I'm not sure if the following is needed
    val tTableIndex = UInt(width = log2Up(transactionTableNumEntries))
    val cacheIndex = UInt(width = log2Up(cacheNumEntries))
  })
  // Response from memory. nnsim-hdl equivalent:
  //   cache_types::mem2cache_struct
  val resp = Decoupled(new DanaBundle()() {
    val done = Bool()
    val data = UInt(width = elementsPerBlock * elementWidth)
    val cacheIndex = UInt(width = log2Up(cacheNumEntries))
    val addr = UInt(width = log2Up(cacheNumBlocks))
    val inUse = Bool()
  }).flip
}

// [TODO] This needs to be moved to the PE or PE Table once those are
// ready
class PECacheInterface extends DanaBundle()() {
  // Inbound request from the PEs. nnsim-hdl equivalent:
  //   pe_types::pe2storage_struct
  val req = Decoupled(new DanaBundle()() {
    val accessType = UInt(width = 1) // [TODO] fragile on Constants.scala
    val peIndex = UInt(width = log2Up(peTableNumEntries))
    val cacheIndex = UInt(width = log2Up(cacheNumEntries))
    val cacheAddr = UInt(width =
      log2Up(cacheNumBlocks * elementsPerBlock * elementWidth))
  }).flip
  // Outbound response to PEs. nnsim-hdl equivalent:
  //   pe_types::storage2pe_struct
  val resp = Decoupled(new DanaBundle()() {
    val accessType = UInt(width = 1) // [TODO] fragile on Constants.scala
    val peIndex = UInt(width = log2Up(peTableNumEntries))
    val data = UInt(width = elementWidth * elementsPerBlock)
    val indexIntoData = UInt(width = elementsPerBlock)
  })
}

class CacheInterface extends Bundle {
  // The cache is connected to memory (technically via the arbiter
  // when this gets added), the control unit, and to the processing
  // elements
  val mem = new CacheMemInterface
  val control = (new ControlCacheInterface).flip
  val pe = new PECacheInterface
}

class Cache extends DanaModule()() {
  val io = new CacheInterface

  // Create the table of cache entries
  val table = Vec.fill(cacheNumEntries){new CacheState}
  // Each cache entry gets one two-ported SRAM
  val mem = Vec.fill(cacheNumEntries){
    Module(new SRAM(
      dataWidth = elementWidth * elementsPerBlock,
      numReadPorts = 0,
      numWritePorts = 0,
      numReadWritePorts = 2,
      sramDepth = cacheNumBlocks // [TODO] I think this is the correct parameter
    )).io}

  // Control Response Pipeline
  val controlRespPipe =
    Vec.fill(2){Reg(Valid(new ControlCacheInterfaceResp))}
  val cacheRead = Vec.fill(cacheNumEntries){
    (Reg(UInt(width=log2Up(cacheNumBlocks)))) }

  // Helper functions for examing the cache entries
  def isFree(x: CacheState): Bool = {!x.valid}
  def isUnused(x: CacheState): Bool = {x.inUseCount === UInt(0)}
  def derefNnid(x: CacheState, y: UInt): Bool = {x.nnid === y}

  // State that we need to derive from the cache
  val hasFree = Bool()
  val hasUnused = Bool()
  val nextFree = UInt()
  val foundNnid = Bool()
  val derefNnid = UInt()
  hasFree := table.exists(isFree)
  hasUnused := table.exists(isUnused)
  nextFree := table.indexWhere(isFree)
  foundNnid := table.exists(derefNnid(_, io.control.req.bits.nnid))
  derefNnid := table.indexWhere(derefNnid(_, io.control.req.bits.nnid))

  io.control.req.ready := hasFree | hasUnused

  // Default values
  io.mem.req.valid := Bool(false)
  io.mem.req.bits.nnid := UInt(0)
  io.mem.req.bits.tTableIndex := UInt(0)
  io.mem.req.bits.cacheIndex := UInt(0)

  io.control.resp.valid := Bool(false)
  io.control.resp.bits.fetch := Bool(false)
  io.control.resp.bits.tableIndex := UInt(0)
  io.control.resp.bits.tableMask := UInt(0)
  io.control.resp.bits.cacheIndex := UInt(0)
  io.control.resp.bits.data := Vec.fill(3){UInt(0)}
  io.control.resp.bits.decimalPoint := UInt(0)
  io.control.resp.bits.field := UInt(0)

  controlRespPipe(0).valid := Bool(false)
  controlRespPipe(0).bits.fetch := Bool(false)
  controlRespPipe(0).bits.tableIndex := UInt(0)
  controlRespPipe(0).bits.tableMask := UInt(0)
  controlRespPipe(0).bits.cacheIndex := UInt(0)
  controlRespPipe(0).bits.data := Vec.fill(3){UInt(0)}
  controlRespPipe(0).bits.decimalPoint := UInt(0)
  controlRespPipe(0).bits.field := UInt(0)
  // Assignment to the output pipe
  controlRespPipe(1) := controlRespPipe(0)

  // debug(controlRespPipe)

  for (i <- 0 until cacheNumEntries) {
    mem(i).din(0) := UInt(0)
    mem(i).addr(0) := UInt(0)
    // mem(i).addr(0) := cacheRead(i)
    mem(i).we(0) := Bool(false)
    // cacheRead(i) := UInt(0)
  }

  // Handle requests from the control module
  when (io.control.req.valid) {
    switch (io.control.req.bits.request) {
      is (e_CACHE_LOAD) {
        when (!foundNnid) {
          // The NNID was not found, so we need to generate a memory
          // request to load it in.

          // Reserve the new cache entry
          table(nextFree).valid := Bool(true)
          table(nextFree).nnid := io.control.req.bits.nnid
          table(nextFree).notifyIndex := io.control.req.bits.tableIndex
          table(nextFree).inUseCount := UInt(1)

          // Generate a request to memory
          io.mem.req.valid := Bool(true)
          io.mem.req.bits.nnid := io.control.req.bits.nnid
          io.mem.req.bits.tTableIndex := io.control.req.bits.tableIndex
          io.mem.req.bits.cacheIndex := nextFree
        } .elsewhen (table(derefNnid).fetch) {
          // The nnid was found, but the data is currently being
          // loaded from memory. This happens if a second request for
          // the same data shows up while this guy is being fetched.
          table(derefNnid).inUseCount := table(derefNnid).inUseCount + UInt(1)
          table(derefNnid).notifyMask := table(derefNnid).notifyMask |
          UIntToOH(io.control.req.bits.tableIndex)
        } .otherwise {
          // The NNID was found and the data has already been loaded
          table(derefNnid).inUseCount := table(derefNnid).inUseCount + UInt(1)

          // Start a response to the control unit
          controlRespPipe(0).valid := Bool(true)
          controlRespPipe(0).bits.tableIndex := io.control.req.bits.tableIndex
          controlRespPipe(0).bits.tableMask :=
            UIntToOH(io.control.req.bits.tableIndex)
          controlRespPipe(0).bits.cacheIndex := derefNnid
          controlRespPipe(0).bits.field := e_CACHE_INFO

          // Read the requested information from the cache
          // mem(derefNnid).addr(0) := UInt(0) // Info is in first blocks
          // cacheRead(derefNnid) := UInt(0)
        }
      }
      is (e_CACHE_LAYER_INFO) {
        controlRespPipe(0).valid := Bool(true)
        controlRespPipe(0).bits.tableIndex := io.control.req.bits.tableIndex
        controlRespPipe(0).bits.field := e_CACHE_LAYER_INFO
        controlRespPipe(0).bits.data(0) := io.control.req.bits.layer

        // Read the layer information from the correct block. A layer
        // occupies one block, so we need to pull the block address
        // out of the layer number.
        mem(derefNnid).addr(0) := UInt(1) + // Offset from info region
          io.control.req.bits.layer(io.control.req.bits.layer.getWidth-1,
            log2Up(elementsPerBlock))
        // cacheRead(derefNnid) := UInt(1) + // Offset from info region
        //   io.control.req.bits.layer(io.control.req.bits.layer.getWidth-1,
        //     log2Up(elementsPerBlock))
      }
      is (e_CACHE_DECREMENT_IN_USE_COUNT) {

      }
    }
  }

  // Pipeline third stage (SRAM read)
  switch (controlRespPipe(0).bits.field) {
    is (e_CACHE_INFO) {
      controlRespPipe(1).bits.decimalPoint :=
      mem(controlRespPipe(0).bits.cacheIndex).dout(0)(UInt(decimalPointWidth),
        UInt(0))
      // Edges [TODO] fragile
      controlRespPipe(1).bits.data(0) :=
      mem(controlRespPipe(0).bits.cacheIndex).dout(0)(48+16, 48)
      // Nodes [TODO] fragile
      controlRespPipe(1).bits.data(1) :=
      mem(controlRespPipe(0).bits.cacheIndex).dout(0)(32+16, 32)
      controlRespPipe(1).bits.data(2) := UInt(0) // Unused
    }
    is (e_CACHE_LAYER_INFO) {
      // Number of neurons in this layer
      controlRespPipe(1).bits.data(0) :=
      mem(controlRespPipe(0).bits.cacheIndex).dout(0)(
        (UInt(elementWidth) *
          controlRespPipe(0).bits.data(0)(log2Up(elementsPerBlock)-1,0)) +
          UInt(12) + UInt(9),
        (UInt(elementWidth) *
          controlRespPipe(0).bits.data(0)(log2Up(elementsPerBlock)-1,0)) +
          UInt(12))
      controlRespPipe(1).bits.data(0) :=
        mem(controlRespPipe(0).bits.cacheIndex).dout(0)(12 + 9, 12)
      // Number of neurons in the next layer
      controlRespPipe(1).bits.data(1) :=
      mem(controlRespPipe(0).bits.cacheIndex).dout(0)(
        (UInt(elementWidth) *
          controlRespPipe(0).bits.data(0)(log2Up(elementsPerBlock)-1,0)) +
          UInt(22)+UInt(9),
        (UInt(elementWidth) *
          controlRespPipe(0).bits.data(0)(log2Up(elementsPerBlock)-1,0)) +
          UInt(22))
      controlRespPipe(1).bits.data(1) :=
        mem(controlRespPipe(0).bits.cacheIndex).dout(0)(22 + 9, 22)
      // Pointer to the first neuron
      controlRespPipe(1).bits.data(2) :=
      mem(controlRespPipe(0).bits.cacheIndex).dout(0)(
        (UInt(elementWidth) *
          controlRespPipe(0).bits.data(0)(log2Up(elementsPerBlock)-1,0)) +
          UInt(0)+UInt(11),
        (UInt(elementWidth) *
          controlRespPipe(0).bits.data(0)(log2Up(elementsPerBlock)-1,0)) +
          UInt(0))
      controlRespPipe(1).bits.data(2) :=
        mem(controlRespPipe(0).bits.cacheIndex).dout(0)(11, 0)
    }
  }

  // Set the response to the control unit
  io.control.resp.valid := controlRespPipe(1).valid
  io.control.resp.bits := controlRespPipe(1).bits

  // Assertions
  assert(!io.control.resp.valid || io.control.resp.ready,
    "Cache trying to send response to Control when Control not ready")
}
