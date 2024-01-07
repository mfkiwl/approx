package approx.multiplication

import chisel3.Bool

import scala.collection.mutable

package object comptree {

  /** Compressor approximation styles */
  abstract class Approximation
  case class NoApproximation() extends Approximation
  case class RowTruncation(rows: Int) extends Approximation
  case class ColumnTruncation(width: Int) extends Approximation
  case class Miscounting(width: Int) extends Approximation
  case class ORCompression(width: Int) extends Approximation

  /** Compressor tree signature
   * 
   * @param signature array of integers denoting the number of bits per column
   */
  class Signature(val signature: Array[Int]) {
    /** Return the length of the signature */
    def length: Int = signature.length

    /** Return a particular element of the signature */
    def apply(log2Weight: Int): Int = signature(log2Weight)

    /** Return this signature in a string representation */
    override def toString(): String = signature.mkString(",")

    /** Compute the signature's total number of inputs */
    lazy val count: Int = signature.sum

    /** Compute the signature's needed output width */
    lazy val outW: Int = {
      // Use BigInts to accumulate over the signature
      var sum = signature.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (count, log2Weight)) =>
        acc + Array.fill(count) { BigInt(0).setBit(log2Weight) }.sum
      }

      // Return the MSB's position
      var pos = 0
      while (sum > 0) {
        pos  += 1
        sum >>= 1
      }
      pos
    }
  }

  /** Compressor tree string signature
   * 
   * @param signature string of comma-separated integers denoting the number of
   *                  bits per column
   */
  class StringSignature(signature: String) extends Signature(signature.split(",").map(_.toInt))

  /** Multiplier compressor tree signature
   * 
   * @param aW the width of the first operand
   * @param bW the width of the second operand
   * @param aSigned whether the first operand is signed (defaults to false)
   * @param bSigned whether the second operand is signed (defaults to false)
   * @param radix the radix of the multiplier (one of 2 or 4, defaults to 2)
   */
  class MultSignature(val aW: Int, val bW: Int, aSigned: Boolean = false, bSigned: Boolean = false, radix: Int = 2)
    extends Signature(MultSignature.genSignature(!(aSigned || bSigned), aW, bW, radix))
  private[comptree] object MultSignature {
    /** Generate the signature for a generic (un-)signed by (un-)signed
     * radix 2 or 4 integer multiplier
     * 
     * @param unsigned whether both input operands are unsigned
     * @param aW the width of the first operand
     * @param bW the width of the second operand
     * @param radix the radix of the multiplier
     * @return an array-formatted signature of the multiplier
     */
    def genSignature(unsigned: Boolean, aW: Int, bW: Int, radix: Int): Array[Int] = {
      require(radix == 2 || radix == 4, "can only generate radix 2 or 4 multiplier signatures")
      if (radix == 2) _genRadix2Signature(unsigned, aW, bW)
      else            _genRadix4Signature(unsigned, aW, bW)
    }

    private[MultSignature] def _genRadix2Signature(unsigned: Boolean, aW: Int, bW: Int): Array[Int] = {
      val midLow  = scala.math.min(aW, bW) - 1
      val midHigh = scala.math.max(aW, bW) - 1
      val upper   = aW + bW - 1

      // Compute and insert the number of bits in each column of the signature
      val pprods = Array.fill(aW + bW)(0)
      (0 until aW).foreach { row =>
        (row until bW + row).foreach { col => pprods(col) += 1 }
      }

      // Insert the sign-extension bits as needed
      if (!unsigned) {
        pprods(midLow)  += 1
        pprods(midHigh) += 1
        pprods(upper)   += 1
      }

      pprods
    }

    private[MultSignature] def _genRadix4Signature(unsigned: Boolean, aW: Int, bW: Int): Array[Int] = {
      val nRows = (aW + 1) / 2

      // Compute and insert the number of bits in each column of the signature
      val pprods = Array.fill(aW + bW)(0)
      (0 until nRows).foreach { row =>
        (0 until bW + (if (unsigned) 1 else 0)).foreach { col => pprods(col + 2 * row) += 1 }
      }

      // Insert the carry bits
      (0 until nRows - (if (unsigned) 1 else 0)).foreach { row => pprods(2 * row) += 1 }

      // Insert the sign bits
      (0 until nRows - (if (unsigned) 1 else 0)).foreach { row =>
        if (row == 0) { // insert (up to) three sign bits
          val start = bW + (if (unsigned) 1 else 0)
          val end   = scala.math.min(bW + 3 + (if (unsigned) 1 else 0), aW + bW)
          (start until end).foreach { col => pprods(col) += 1}
        } else { // insert (up to) one sign bit
          val col = bW + 2 * row + (if (unsigned) 1 else 0)
          if (col < (aW + bW)) pprods(col) += 1
        }
      }

      // Insert the constant bits as needed (bW + 1 bit for shift + 3 sign bits)
      (bW + 3 + (if (unsigned) 1 else 0) until aW + bW by 2).foreach { col => pprods(col) += 1 }

      pprods
    }
  }

  /** Counter fitness metrics */
  private[comptree] object FitnessMetric extends Enumeration {
    type FitnessMetric = Value
    val Strength   = Value("strength")
    val Efficiency = Value("efficiency")
  }
  import FitnessMetric._

  /** Compressor generation state
   * 
   * Used to track how many counters are used in each stage, as needed
   * when generating compressors for FPGAs.
   */
  private[comptree] class State {
    var stages:   Int = 0
    var counters: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty[Int]

    /** Add a stage */
    def addStage(): Unit = stages += 1

    /** Add a counter to this stage */
    def addCounter(): Unit = {
      counters.addAll(Array.fill(stages - counters.length + 1)(0))
      counters(stages) += 1
    }
  }

  /** Compressor generation context
   * 
   * @param outW the targeted output width of the compressor tree
   * @param targetDevice the device to target generation for (if left empty, picks ASIC,
   *                     can also be one of "7series", "ultrascale", "versal", "intel")
   * @param approx the type of approximation to use in the compressor tree
   * @param mtrc which fitness metric to use for selecting counters
   * 
   * Used to control generation compressor tree generation, including
   * the selection of counters, the compression goal, and the final adder.
   */
  private[comptree] class Context(val outW: Int, targetDevice: String, val approx: Approximation, mtrc: Char) {
    private val _device: String = targetDevice.toLowerCase()
    private val _mtrc: Char = mtrc.toLower
    val goal: Int = _device match {
      case "7series" | "ultrascale" => 3
      case _ => 2
    }
    val metric = _mtrc match {
      case 's' => Strength
      case 'e' => Efficiency
      case _ =>
        throw new IllegalArgumentException(s"unsupported fitness metric ${_mtrc}, must be one of 'e' (efficiency) or 's' (strength)")
    }
    val counters = _device match {
      case "7series" | "ultrascale" => Counters.SevenSeries
      case "versal" => Counters.Versal
      case "intel" => Counters.Intel
      case _ => Counters.ASIC
    }
  }

  /** Bit matrix class */
  private[comptree] class BitMatrix {
    // Stack-based bit matrix storage
    val bits = mutable.ArrayBuffer.empty[mutable.Stack[Bool]]

    /** Return the length of the matrix */
    def length: Int = bits.length

    /** Compute the matrix's total number of bits */
    def count: Int = bits.foldLeft(0) { case (acc, col) => acc + col.size }

    /** Compute the matrix's number of bits in a given column
     * 
     * Returns 0 for columns that do not exist.
     */
    def colCount(col: Int): Int = if (bits.isDefinedAt(col)) bits(col).size else 0

    /** Insert a bit into the matrix
     * 
     * @param bit the bit to add
     * @param log2Weight the bit's index
     */
    def insertBit(bit: Bool, log2Weight: Int): Unit = {
      // Extend the bit matrix to the expected length
      bits.addAll(Array.fill(log2Weight - bits.length + 1) { mutable.Stack.empty[Bool] })

      // Now insert the bit
      bits(log2Weight).push(bit)
    }

    /** Pop and return a bit from the matrix
     * 
     * @param log2Weight the bit's index
     */
    def popBit(log2Weight: Int): Bool = {
      assume(bits.length > log2Weight && bits(log2Weight).nonEmpty,
        s"bit matrix with ${bits.length} columns cannot pop bit from column ${log2Weight}")
      bits(log2Weight).pop()
    }

    /** Check if the bit matrix meets a compression goal
     * 
     * @param goal the compression goal in bits
     */
    def meetsGoal(goal: Int): Boolean = bits.forall(_.size <= goal)
  }
}
