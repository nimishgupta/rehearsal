import java.nio.file.Path
import scala.annotation.tailrec

package object equiv {

  def ancestors(pathSet: Set[Path]): Set[Path] = {
    @tailrec
    def loop(p: Path, result: Set[Path]): Set[Path] = {
      // Check if we have already solved this problem
      if (!result(p)) {
        p.getParent match {
          case null => result
          case parent: Path => loop(parent, result + p.normalize)
        }
      }
      else {
        result
      }
    }

    pathSet.foldLeft(pathSet) { (pathSet, path) => loop(path, pathSet) }
  }

}