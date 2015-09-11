class SimpleParserTests extends org.scalatest.FunSuite {

  import parser.Syntax._
  import parser.Parser._
  import parser.ParseError
  import java.nio.file._
  import scala.collection.JavaConversions._

  for (path <- Files.newDirectoryStream(Paths.get("parser-tests/good"))) {

    test(path.toString) {
      parseFile(path.toString)
    }

  }

  for (path <- Files.newDirectoryStream(Paths.get("parser-tests/bad"))) {

    test(path.toString) {
      intercept[ParseError] {
        parseFile(path.toString)
      }
    }

  }

}