package chicala.sort

import scala.tools.nsc
import nsc.Global

import chicala.util.Format

trait StatementProcess extends Format {
  val global: Global
  import global._

  val treeCopy: TreeCopier

  case class ConnectedSignals(val fully: Set[String], val partially: Set[String], val dependency: Set[String]) {
    def ++(that: ConnectedSignals): ConnectedSignals = {
      ConnectedSignals(
        this.fully ++ that.fully,
        this.partially ++ that.partially,
        this.dependency ++ that.dependency
      )
    }
  }
  object ConnectedSignals {
    def empty = ConnectedSignals(Set.empty, Set.empty, Set.empty)
  }

  sealed abstract class Statement(val tree: Tree, val signals: ConnectedSignals) {
    def markInvalidConnect(connected: Set[String]): Statement
  }

  case class Connect(
      override val tree: Tree,
      override val signals: ConnectedSignals,
      val valid: Boolean
  ) extends Statement(tree, signals) {
    def markInvalidConnect(connected: Set[String]): Connect = {
      assert(signals.fully.size == 1, s"Connect should only has 1 fully connected signal:\n${this}")

      if (connected.contains(signals.fully.head))
        Connect(tree, signals, false)
      else
        this
    }
    def apply() = tree
  }
  case class When(
      override val tree: Tree,
      val gen: List[Tree] => Tree,
      override val signals: ConnectedSignals,
      val condition: Tree,
      val content: Statements
  ) extends Statement(tree, signals) {
    def markInvalidConnect(connected: Set[String]): When = {
      val newContent = content.markInvalidConnect(connected)
      When(tree, gen, signals, condition, newContent)
    }
    def apply(x: List[Tree]) = gen(x)
  }
  case class WhenOtherwise(
      override val tree: Tree,
      val gen: (List[Tree], List[Tree]) => Tree,
      override val signals: ConnectedSignals,
      val when: When,
      val content: Statements
  ) extends Statement(tree, signals) {
    def markInvalidConnect(connected: Set[String]): WhenOtherwise = {
      val newWhen    = when.markInvalidConnect(connected)
      val newContent = content.markInvalidConnect(connected)
      WhenOtherwise(tree, gen, signals, newWhen, newContent)
    }
    def apply(whenX: List[Tree], otherX: List[Tree]) = gen(whenX, otherX)
  }

  object Statement {
    def formTree(tree: Tree): Option[Statement] = {
      if (check(tree)) analysis(tree)
      else None
    }

    def analysis(tree: Tree): Option[Statement] = analysis(tree, tree, x => x)
    def analysis(tree: Tree, treeRoot: Tree, gen: Tree => Tree): Option[Statement] = {
      def genBlockOnDemand(treeList: List[Tree]): List[Tree] = {
        treeList.length match {
          case 0 => List(Literal(Constant(())))
          case 1 => treeList
          case _: Int =>
            var rev = treeList.reverse
            List(Block(rev.tail.reverse, rev.head))
        }
      }

      tree match {
        // SourceInfo
        case Apply(fun, args)
            if (fun.tpe.toString().startsWith("(implicit sourceInfo: chisel3.internal.sourceinfo.SourceInfo")) => {
          analysis(fun, treeRoot, x => treeCopy.Apply(tree, x, args)) // pass through
        }
        case Typed(expr, tpt) => {
          analysis(expr, treeRoot, x => gen(treeCopy.Typed(tree, x, tpt))) // pass through
        }
        // assert
        case _: Apply if (tree.tpe.toString().endsWith(": chisel3.assert.Assert")) => {
          // not process
          None
        }
        // otherwise
        case Apply(Select(whenTree, TermName("otherwise")), bodyList) => {
          val when    = analysis(whenTree).get.asInstanceOf[When]
          val content = analysisList(bodyList)

          val whenSigs = when.signals
          val bodySigs = content.signals
          val fully    = whenSigs.fully.intersect(bodySigs.fully) // fully connected in both side
          val tmp      = whenSigs ++ bodySigs

          val signals = ConnectedSignals(fully, tmp.partially ++ tmp.fully -- fully, tmp.dependency)
          val newGen: (List[Tree], List[Tree]) => Tree = {
            val select = tree.asInstanceOf[Apply].fun.asInstanceOf[Select]
            (whenX, otherX) =>
              gen(treeCopy.Apply(tree, treeCopy.Select(select, when(whenX), select.name), genBlockOnDemand(otherX)))
          }

          Some(WhenOtherwise(treeRoot, newGen, signals, when, content))
        }
        // when only
        case Apply(
              Apply(
                Select(Select(Ident(TermName("chisel3")), TermName("when")), TermName("apply")),
                conditionList
              ),
              bodyList
            ) => {
          assert(conditionList.length == 1, "`when` should have only 1 condition")
          assert(bodyList.length == 1, "`when` should have only 1 block or statement in body")

          val conditionTree = conditionList.head
          val conditionDeps = processExpression(conditionTree)

          val content  = analysisList(bodyList)
          val bodySigs = content.signals
          val signals  = ConnectedSignals(bodySigs.fully, bodySigs.partially, bodySigs.dependency ++ conditionDeps)
          val newGen: List[Tree] => Tree = {
            val apply = tree.asInstanceOf[Apply].fun.asInstanceOf[Apply]
            x => gen(treeCopy.Apply(tree, treeCopy.Apply(apply, apply.fun, conditionList), genBlockOnDemand(x)))
          }

          Some(When(treeRoot, newGen, signals, conditionTree, content))
        }
        // :=
        case Apply(Select(left: Select, TermName("$colon$eq")), rights) => {
          assert(rights.length == 1, "':=' should have only 1 operand on the right")

          val deps    = rights.map(processExpression(_)).reduce(_ ++ _)
          val signals = ConnectedSignals(Set(left.toString()), Set(), deps)

          Some(Connect(treeRoot, signals, true))
        }
        case Apply(fun, args) => {
          global.reporter.error(
            tree.pos,
            s"""chicala-sort: unknown AST `Apply` in analysis:
              |tree.tpe:
              |  ${tree.tpe}
              |tree:
              |  ${tree}
              |tree AST:
              |  ${showRaw(tree)}
              |source code:""".stripMargin
          )
          None
        }

        case ValDef(_, _, _, _) => {
          // pass for now
          None
        }
        // for empty statement body
        case Literal(Constant(())) => {
          None
        }
        case _: Tree => {
          global.reporter.error(
            tree.pos,
            s"""chicala-sort: unknown AST in analysis:
              |tree.tpe:
              |  ${tree.tpe}
              |tree:
              |  ${tree}
              |tree AST:
              |  ${showRaw(tree)}
              |source code:""".stripMargin
          )
          None
        }
      }

    }
    def analysisList(treeList: List[Tree]): Statements = {
      Statements(
        treeList
          .map({
            case Block(stats, expr) => {
              (stats :+ expr).map(analysis(_))
            }
            case t: Tree => {
              List(analysis(t))
            }
          })
          .flatten
          .flatten
      )
    }

    private def processExpression(tree: Tree): Set[String] = {
      tree match {
        // SourceInfo
        case Apply(fun, args)
            if (fun.tpe.toString().startsWith("(implicit sourceInfo: chisel3.internal.sourceinfo.SourceInfo")) => {
          processExpression(fun) // pass through
        }
        case Typed(expr, tpt) => {
          processExpression(expr) // pass through
        }
        // literal
        case Select(
              Apply(Select(Select(Ident(TermName("chisel3")), TermName("package")), TermName("fromIntToLiteral")), _),
              _
            ) => {
          Set.empty
        }
        // signal
        case s: Select if (List("chisel3.Bool", "chisel3.UInt").contains(tree.tpe.toString())) => {
          Set(s.toString())
        }
        // operator
        case Apply(Select(left: Select, op), rights) if (List("do_$plus").contains(op.toString())) => {
          assert(rights.length == 1, "'do_+' should have only 1 operand on the right")

          val sigs = rights.map(processExpression(_)).reduce(_ ++ _)
          Set(left.toString()) ++ sigs
        }
        case _: Tree => {
          println()
          println(tree.tpe)
          println(tree)
          println()
          println(showRaw(tree))
          println("processExpression")
          println("**********")
          Set.empty
        }
      }
    }

    def filter(tree: Tree): Option[Tree] = {
      if (check(tree)) Some(tree)
      else None
    }
    def check(tree: Tree): Boolean = {
      tree.exists {
        case Apply(fun, args) =>
          fun.tpe.toString().startsWith("(implicit sourceInfo: chisel3.internal.sourceinfo.SourceInfo")
        case _ => false
      }
    }
  }

  /** Collection of statement
    *
    * @param body
    *   list of statement
    */
  case class Statements(val body: List[Statement], val signals: ConnectedSignals) {

    /** Mark invalid connect couse by last connect semantics
      *
      * @param connected
      *   signal names that have already been fully connected
      * @return
      *   new Statements
      */
    def markInvalidConnect(connected: Set[String] = Set.empty): Statements = {
      var connectedForNow = connected
      val newBody = (for (s <- body.reverse) yield {
        val newStatement = s.markInvalidConnect(connectedForNow)
        connectedForNow = connectedForNow ++ s.signals.fully
        newStatement
      }).reverse
      Statements(newBody, signals)
    }

    lazy val dependencyGraph: DirectedGraph = {
      import scala.collection.mutable

      val lastConnect = mutable.Map.empty[String, Id]
      val vertexs     = mutable.Set.empty[Vertex]
      val edges       = mutable.Set.empty[DirectedEdge]

      def getVertexAndLastConnectDependcy(idPrefix: Id, statements: Statements): Unit = {
        val b = statements.body
        (1 to b.length).map(idPrefix :+ _).zip(b).foreach { case (id, statement) =>
          statement match {
            case Connect(tree, signals, valid) => {
              vertexs += Vertex(id)

              val left = signals.fully.head
              if (lastConnect.contains(left)) {
                edges += DirectedEdge(Vertex(id), Vertex(lastConnect(left)))
              }
              lastConnect(left) = id
            }
            case When(tree, gen, signals, condition, content) => {
              getVertexAndLastConnectDependcy(id, content)
            }
            case WhenOtherwise(tree, gen, signals, when, content) => {
              getVertexAndLastConnectDependcy(id :+ 1, when.content)
              getVertexAndLastConnectDependcy(id :+ 2, content)
            }
          }
        }
      }

      def getConnectDependcy(idPrefix: Id, statements: Statements, depSigalPrefix: Set[String]): Unit = {
        val b = statements.body
        (1 to b.length).map(idPrefix :+ _).zip(b).foreach { case (id, statement) =>
          statement match {
            case Connect(tree, signals, valid) => {
              if (valid) {
                (signals.dependency ++ depSigalPrefix).foreach { sig =>
                  if (lastConnect.contains(sig)) {
                    edges += DirectedEdge(Vertex(id), Vertex(lastConnect(sig)))
                  } // else, sig is never been connected or is an input
                }
              }
            }
            case When(tree, gen, signals, condition, content) => {
              getConnectDependcy(id, content, depSigalPrefix + condition.toString())
            }
            case WhenOtherwise(tree, gen, signals, when, content) => {
              getConnectDependcy(id :+ 1, when.content, depSigalPrefix + when.condition.toString())
              getConnectDependcy(id :+ 2, content, depSigalPrefix + when.condition.toString())
            }
          }
        }
      }

      getVertexAndLastConnectDependcy(Id.empty, this)
      getConnectDependcy(Id.empty, this, Set.empty)

      DirectedGraph(vertexs.toSet, edges.toSet)
    }

    def trees: List[Tree] = body.map(_.tree)

    def mergeId(idList: List[Id]): List[(Int, List[Id])] = {
      idList
        .foldLeft(List.empty[(Int, List[Id])]) { case (ls, id) =>
          ls match {
            case Nil => List((id.top, List(id.rest)))
            case (index, restList) :: next =>
              if (index == id.top) {
                (index, id.rest :: restList) :: next
              } else {
                (id.top, List(id.rest)) :: ls
              }

          }
        }
        .map(x => (x._1, x._2.reverse))
        .reverse
    }

    def generate(idList: List[Id]): List[Tree] = {
      val bodyArray = body.toArray

      mergeId(idList).map { case (index, restList) =>
        bodyArray(index - 1) match {
          case c: Connect => c()
          case w: When =>
            val trees = w.content.generate(restList)
            w(trees)
          case o: WhenOtherwise =>
            val merged = mergeId(restList).toMap
            val whenX =
              if (merged.contains(1)) o.when.content.generate(merged(1))
              else List.empty
            val otherX =
              if (merged.contains(2)) o.content.generate(merged(2))
              else List.empty
            o(whenX, otherX)
        }
      }
    }
  }
  object Statements {
    def apply(body: List[Statement]): Statements =
      if (body.isEmpty) Statements.empty
      else Statements(body, body.map(_.signals).reduce(_ ++ _))

    def fromTreeList(treeList: List[Tree]): Statements = {
      Statements(treeList.map(Statement.formTree(_)).flatten)
    }
    def empty = Statements(List.empty, ConnectedSignals.empty)
  }
}
