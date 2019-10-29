package io.shiftleft.semanticcpg.codedumper

import io.shiftleft.codepropertygraph.generated.{Languages, nodes}
import io.shiftleft.semanticcpg.language._
import io.shiftleft.semanticcpg.language.NodeSteps
import better.files._
import io.shiftleft.codepropertygraph.Cpg
import org.apache.logging.log4j.LogManager

import sys.process._
import scala.util.Try

object CodeDumper {

  private val logger = LogManager.getLogger(CodeDumper)
  val arrow: CharSequence = "// ===>\n"

  /**
    * Evaluate the `step` and determine associated locations.
    * Dump code at those locations
    * */
  def dump[NodeType <: nodes.StoredNode](step: NodeSteps[NodeType], highlight: Boolean = true): String = {
    val cpg = new Cpg(step.graph)
    step.location.l.map(dump(_, highlight, cpg)).mkString("\n")
  }

  /**
    * Dump string representation of code at given `location`.
    * */
  private def dump(location: nodes.NewLocation, highlight: Boolean, cpg: Cpg): String = {
    val filename = location.filename

    if (location.node.isEmpty) {
      logger.warn("Empty `location.node` encountered")
      return ""
    }

    val node = location.node.get
    val language = cpg.metaData.language.headOption()
    if (language.isEmpty || !Set(Languages.C).contains(language.get)) {
      println(language)
      logger.info("dump not supported for this language or language not set in CPG")
      return ""
    }

    val method = node match {
      case n: nodes.Method     => Some(n)
      case n: nodes.Expression => Some(n.method)
      case _                   => None
    }

    val lineToHighlight = location.lineNumber
    method
      .collect {
        case m: nodes.Method if m.lineNumber.isDefined && m.lineNumberEnd.isDefined =>
          val rawCode = code(filename, m.lineNumber.get, m.lineNumberEnd.get, lineToHighlight)
          if (highlight) {
            externalHighlighter(rawCode, language)
          } else {
            rawCode
          }
      }
      .getOrElse("")
  }

  /**
    * For a given `filename`, `startLine`, and `endLine`, return the corresponding code
    * by reading it from the file. If `lineToHighlight` is defined, then a line containing
    * an arrow (as a source code comment) is included right before that line.
    * */
  def code(filename: String, startLine: Integer, endLine: Integer, lineToHighlight: Option[Integer] = None): String = {
    val lines = Try(File(filename).lines.toList).getOrElse {
      logger.warn("error reading from: " + filename);
      List()
    }
    lines
      .slice(startLine - 1, endLine)
      .zipWithIndex
      .map {
        case (line, lineNo) =>
          if (lineToHighlight.isDefined && lineNo == lineToHighlight.get - startLine) {
            arrow + " " + line
          } else {
            line
          }
      }
      .mkString("\n")
  }

  private def externalHighlighter(code: String, language: Option[String]): String = {

    val langFlag = language match {
      case Some(Languages.C) => "-sC"
      case _                 => throw new RuntimeException("Attempting to call highlighter on unsupported language")
    }

    val f = File.newTemporaryFile("dump")
    f.writeText(code)
    val ret = try {
      val highlightedCode: String = Process(Seq("source-highlight-esc.sh", f.path.toString, langFlag)).!!
      highlightedCode
    } catch {
      case exception: Exception =>
        logger.info("syntax highlighting now working. Is `source-highlight` installed?")
        logger.info(exception)
        ""
    } finally {
      f.delete()
    }
    ret
  }

}