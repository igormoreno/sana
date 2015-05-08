package ch.usi.inf.l3.sana.tiny.contexts



trait TreeId {
  def kind: TreeType
  def unitId: Int
  def treeId: Int 
}

object TreeId {
  class TreeIdImpl(val kind: TreeType,
    val unitId: Int, val treeId: Int) extends TreeId

  def apply(kind: TreeType, unitId: Int, treeId: Int): TreeId =
    new TreeIdImpl(kind, unitId, treeId)
}

abstract class TreeType
object MethodId extends TreeType
object PackageId extends TreeType
object ClassId extends TreeType
object InterfaceId extends TreeType
object VarId extends TreeType


