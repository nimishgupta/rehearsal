package fsmodel

import java.nio.file.Path
import Implicits._

class TypedZ3Tests extends org.scalatest.FunSuite {

	val z = new Z3Impl

  test("Z3Bools are distinct") {
    assert(z.z3true != z.z3false)
  }

  test("Z3FileStates are distinct") {
    assert(z.isFile != z.isDir)
    assert(z.isFile != z.doesNotExist)
  }

}
