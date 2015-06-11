package ch.usi.inf.l3.sana.primj.typechecker

import ch.usi.inf.l3.sana
import sana.tiny
import sana.calcj
import sana.primj
import tiny.util.{CompilationUnits, MonadUtils}
import tiny.passes
import tiny.report._
import tiny.contexts.TreeContexts
import calcj.typechecker
import calcj.ast.JavaOps._
import primj.report._
import primj.Global

import scalaz.Scalaz._
import scalaz._

// TODO: How long should we keep def information in our database?

// From Java Specification 1.0 - Sect: 5.2 - p61
// 1- Assignment Conversion
// 2- Method Conversion   Sect: 5.3 - p66
// 3- String Conversion   Sect: 5.4 - p67

trait Typers extends typechecker.Typers {
  override type G <: Global
  import global._

  trait Typer extends super.Typer {

    override def typeTree(tree: Tree): TypeChecker[Tree] = tree match {
      case tmpl: Template  => for {
          typedMembers <- tmpl.members.map(typeDefTree(_)).sequenceU
          r            <- pointSW(Template(typedMembers, tmpl.owner))
        } yield r
      case dtree: TermTree => for {
        ttree <- typeTermTree(dtree)
      } yield ttree
      case s: Expr         => for {
        ts <- typeExpr(s)
      } yield ts
      case _               => 
        super.typeTree(tree)
    }

    def typeDefTree(dtree: DefTree): TypeChecker[DefTree] = dtree match {
      case ttree: TermTree     => for {
        r <- typeTermTree(ttree)
      } yield r
      case _                   => pointSW(dtree)
    }

    def typeTermTree(dtree: TermTree): TypeChecker[TermTree] = dtree match {
      case vdef: ValDef     => for {
        ttree <- typeValDef(vdef)
      } yield ttree
      case mdef: MethodDef  => for {
        ttree <- typeMethodDef(mdef)
      } yield ttree
    }


    def typeMethodDef(mdef: MethodDef): TypeChecker[MethodDef] = for {
      params   <- mdef.params.map(typeValDef(_)).sequenceU
      body     <- typeExpr(mdef.body)
      rhsty    <- toTypeChecker(body.tpe)
      rty      <- toTypeChecker(mdef.ret.tpe)
      _        <- (rhsty <:< rty) match {
        case false =>
          error(TYPE_MISMATCH,
            rhsty.toString, rty.toString, body.pos, mdef)
          pointSW(())
        case true  =>
          pointSW(())
      }
      tree    <- pointSW(MethodDef(mdef.mods, mdef.id, mdef.ret, mdef.name, 
                                  params, body, mdef.pos, mdef.owner))
    } yield tree

    def typeValDef(vdef: ValDef): TypeChecker[ValDef] = for {
      rhs      <- typeExpr(vdef.rhs)
      rhsty    <- toTypeChecker(rhs.tpe)
      ctx      <- getSW
      vty      <- toTypeChecker(vdef.tpt.tpe)
      _        <- if(vty =:= VoidType) {
        error(VOID_VARIABLE_TYPE,
            vty.toString, vty.toString, rhs.pos, vdef)
        pointSW(())
      } else (rhsty <:< vty) match {
        case false =>
          error(TYPE_MISMATCH,
            rhsty.toString, vty.toString, rhs.pos, vdef)
          pointSW(())
        case true  =>
          pointSW(())
      }
      tree <- pointSW(ValDef(vdef.mods, vdef.id, vdef.tpt, vdef.name, 
                            rhs, vdef.pos, vdef.owner))
    } yield tree


    override def typeExpr(e: Expr): TypeChecker[Expr] = e match {
      case iff: If                => for {
        ti <- typeIf(iff)
      } yield ti
      case wile: While            => for {
        tw <- typeWhile(wile)
      } yield tw
      case forloop: For           => for {
        tf <- typeFor(forloop)
      } yield tf
      case (_: Lit) | (_: Cast)   => pointSW(e)
      case apply: Apply           => for {
        tapp <- typeApply(apply)
      } yield tapp
      // FIXME: Typechecking for Block is missing
      case _                      => 
        super.typeExpr(e)
    }

    def typeWhile(wile: While): TypeChecker[While] = for {
      cond <- typeExpr(wile.cond)
      body <- typeExpr(wile.body)
      tpe  <- toTypeChecker(cond.tpe)
      _    <- (tpe =/= BooleanType) match {
        case true => 
          error(TYPE_MISMATCH,
            tpe.toString, "boolean", wile.cond.pos, wile.cond)
          pointSW(()) 
        case _    => pointSW(())
      }
      tree <- pointSW(While(wile.mods, cond, body, wile.pos))
    } yield tree

    def typeFor(forloop: For): TypeChecker[For] = for {
      inits <- forloop.inits.map(typeTree(_)).sequenceU
      cond  <- typeExpr(forloop.cond)
      steps <- forloop.steps.map(typeExpr(_)).sequenceU
      body  <- typeExpr(forloop.body)
      tpe   <- toTypeChecker(cond.tpe)
      _     <- (tpe =/= BooleanType) match {
        case true =>
          error(TYPE_MISMATCH,
            tpe.toString, "boolean", forloop.cond.pos, forloop.cond)
          pointSW(()) 
        case _    => pointSW(())
      }
      _     <- inits.filter(isValDefOrStatementExpression(_)) match {
        case (x::xs) =>
          error(BAD_STATEMENT, x.toString,
            "An expression statement, or variable declaration", x.pos, x)
          pointSW(())
        case _       => pointSW(())
      }
      _     <- steps.filter(!isValidStatementExpression(_)) match {
        case (x::xs) =>
          error(BAD_STATEMENT, x.toString,
            "An expression statement, or more", x.pos, x)
          pointSW(())
        case _       => pointSW(())
      }
      tree  <- pointSW(For(forloop.id, inits, cond, steps, body, forloop.pos))
    } yield tree

    def typeIf(iff: If): TypeChecker[If] = for {
      cond  <- typeExpr(iff.cond)
      thenp <- typeExpr(iff.thenp)
      elsep <- typeExpr(iff.elsep)
      tpe   <- toTypeChecker(cond.tpe)
      _     <- (tpe =/= BooleanType) match {
        case true =>
          error(TYPE_MISMATCH,
            tpe.toString, "boolean", iff.cond.pos, iff.cond)
          pointSW(()) 
        case _    => pointSW(())
      }
      tree  <- pointSW(If(cond, thenp, elsep, iff.pos))
    } yield tree

    def typeApply(app: Apply): TypeChecker[Apply] = for {
      fun       <- typeExpr(app.fun)
      funty     <- toTypeChecker(fun.tpe)
      args      <- app.args.map(typeExpr(_)).sequenceU
      argtys    <- args.map((x) => toTypeChecker(x.tpe)).sequenceU
      _         <- funty match {
        case MethodType(r, ts) if checkList[Type](argtys, ts, _ <:< _) =>
          pointSW(())
        case t: MethodType                                             =>
          // TODO: Fix the error message
          error(TYPE_MISMATCH,
            "", "", app.pos, app)
          pointSW(())
        case t                                                         =>
          error(BAD_STATEMENT,
            t.toString, "function/method type", app.pos, app)
          pointSW(())
      }
      tree     <- pointSW(Apply(fun, args, app.pos, app.owner))
    } yield tree


    protected def isValDefOrStatementExpression(v: Tree): Boolean = v match {
      case s: ValDef => true
      case e: Expr   => isValidStatementExpression(e)
      case _         => false
    }
    protected def isValidStatementExpression(e: Expr): Boolean = e match {
      case _: Postfix    => true
      case Unary(Inc, _) => true
      case Unary(Dec, _) => true
      case _: Apply      => true
      // case _: New        => true
      case _: Assign     => true
    }
  }
}
