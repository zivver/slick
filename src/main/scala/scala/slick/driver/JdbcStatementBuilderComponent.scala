package scala.slick.driver

import scala.language.{existentials, implicitConversions}
import scala.slick.SlickException
import scala.slick.ast._
import scala.slick.ast.Util.nodeToNodeOps
import scala.slick.ast.ExtraUtil._
import scala.slick.util._
import scala.slick.util.MacroSupport.macroSupportInterpolation
import scala.slick.lifted._
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.slick.compiler.{Phase, CompilationState}
import scala.slick.profile.SqlProfile

trait JdbcStatementBuilderComponent { driver: JdbcDriver =>

  // Create the different builders -- these methods should be overridden by drivers as needed
  def createQueryTemplate[P,R](q: Query[_, R]): JdbcQueryTemplate[P,R] = new JdbcQueryTemplate[P,R](q, this)
  def createQueryBuilder(input: QueryBuilderInput): QueryBuilder = new QueryBuilder(input)
  def createInsertBuilder(node: Node): InsertBuilder = new InsertBuilder(node)
  def createTableDDLBuilder(table: Table[_]): TableDDLBuilder = new TableDDLBuilder(table)
  def createColumnDDLBuilder(column: FieldSymbol, table: Table[_]): ColumnDDLBuilder = new ColumnDDLBuilder(column)
  def createSequenceDDLBuilder(seq: Sequence[_]): SequenceDDLBuilder = new SequenceDDLBuilder(seq)

  abstract class StatementPart
  case object SelectPart extends StatementPart
  case object FromPart extends StatementPart
  case object WherePart extends StatementPart
  case object OtherPart extends StatementPart

  /** Builder for SELECT and UPDATE statements. */
  class QueryBuilder(val input: QueryBuilderInput) { queryBuilder =>

    // Immutable config options (to be overridden by subclasses)
    protected val scalarFrom: Option[String] = None
    protected val supportsTuples = true
    protected val supportsCast = true
    protected val concatOperator: Option[String] = None
    protected val useIntForBoolean = false
    protected val hasPiFunction = true
    protected val hasRadDegConversion = true
    protected val pi = "3.1415926535897932384626433832795"

    // Mutable state accessible to subclasses
    protected val b = new SQLBuilder
    protected var currentPart: StatementPart = OtherPart
    protected val symbolName = new QuotingSymbolNamer(Some(input.state.symbolNamer))
    protected val joins = new HashMap[Symbol, Join]

    def sqlBuilder = b

    final def buildSelect(): QueryBuilderResult = {
      buildComprehension(toComprehension(input.ast, true))
      QueryBuilderResult(b.build, input.linearizer)
    }

    protected final def newSym = new AnonSymbol

    @inline protected final def building(p: StatementPart)(f: => Unit): Unit = {
      val oldPart = currentPart
      currentPart = p
      f
      currentPart = oldPart
    }

    protected def toComprehension(n: Node, liftExpression: Boolean = false): Comprehension = n match {
      case c : Comprehension => c
      case p: Pure =>
        Comprehension(select = Some(p))
      case t: TableNode =>
        Comprehension(from = Seq(t.nodeTableSymbol -> t))
      case u: Union =>
        Comprehension(from = Seq(newSym -> u))
      case n =>
        if(liftExpression) toComprehension(Pure(n))
        else throw new SlickException("Unexpected node "+n+" -- SQL prefix: "+b.build.sql)
    }

    protected def buildComprehension(c: Comprehension): Unit = {
      scanJoins(c.from)
      buildSelectClause(c)
      buildFromClause(c.from)
      buildWhereClause(c.where)
      buildGroupByClause(c.groupBy)
      buildOrderByClause(c.orderBy)
      buildFetchOffsetClause(c.fetch, c.offset)
    }

    protected def buildSelectClause(c: Comprehension) = building(SelectPart) {
      b"select "
      buildSelectModifiers(c)
      c.select match {
        case Some(Pure(StructNode(ch))) =>
          b.sep(ch, ", ") { case (sym, n) =>
            buildSelectPart(n)
            b" as `$sym"
          }
        case Some(Pure(ProductNode(ch))) =>
          b.sep(ch, ", ")(buildSelectPart)
        case Some(Pure(n)) => buildSelectPart(n)
        case None =>
          if(c.from.length <= 1) b"*"
          else b"`${c.from.last._1}.*"
      }
    }

    protected def buildSelectModifiers(c: Comprehension) {}

    protected def scanJoins(from: Seq[(Symbol, Node)]) {
      for((sym, j: Join) <- from) {
        joins += sym -> j
        scanJoins(j.nodeGenerators)
      }
    }

    protected def buildFromClause(from: Seq[(Symbol, Node)]) = building(FromPart) {
      if(from.isEmpty) scalarFrom.foreach { s => b" from $s" }
      else {
        b" from "
        b.sep(from, ", ") { case (sym, n) => buildFrom(n, Some(sym)) }
      }
    }

    protected def buildWhereClause(where: Seq[Node]) = building(WherePart) {
      if(!where.isEmpty) {
        b" where "
        expr(where.reduceLeft((a, b) => Library.And.typed(typeMapperDelegates.booleanTypeMapperDelegate, a, b)), true)
      }
    }

    protected def buildGroupByClause(groupBy: Option[Node]) = building(OtherPart) {
      groupBy.foreach { e => b" group by !$e" }
    }

    protected def buildOrderByClause(order: Seq[(Node, Ordering)]) = building(OtherPart) {
      if(!order.isEmpty) {
        b" order by "
        b.sep(order, ", "){ case (n, o) => buildOrdering(n, o) }
      }
    }

    protected def buildFetchOffsetClause(fetch: Option[Long], offset: Option[Long]) = building(OtherPart) {
      (fetch, offset) match {
        /* SQL:2008 syntax */
        case (Some(t), Some(d)) => b" offset $d row fetch next $t row only"
        case (Some(t), None) => b" fetch next $t row only"
        case (None, Some(d)) => b" offset $d row"
        case _ =>
      }
    }

    protected def buildSelectPart(n: Node): Unit = n match {
      case Typed(t: TypeMapper[_]) if useIntForBoolean && (t(profile) == driver.typeMapperDelegates.booleanTypeMapperDelegate) =>
        b"case when $n then 1 else 0 end"
      case n =>
        expr(n, true)
    }

    protected def buildFrom(n: Node, alias: Option[Symbol], skipParens: Boolean = false): Unit = building(FromPart) {
      def addAlias = alias foreach { s => b += ' ' += symbolName(s) }
      n match {
        case t @ TableNode(name) =>
          b += quoteIdentifier(name)
          if(alias != Some(t.nodeTableSymbol)) addAlias
        case j @ Join(leftGen, rightGen, left, right, jt, on) =>
          buildFrom(left, Some(leftGen))
          b" ${jt.sqlName} join "
          buildFrom(right, Some(rightGen))
          on match {
            case LiteralNode(true) =>
            case _ => b" on !$on"
          }
        case Union(left, right, all, _, _) =>
          b"\("
          buildFrom(left, None, true)
          if(all) b" union all " else b" union "
          buildFrom(right, None, true)
          b"\)"
          addAlias
        case n =>
          b"\("
          buildComprehension(toComprehension(n, true))
          b"\)"
          addAlias
      }
    }

    def expr(n: Node, skipParens: Boolean = false): Unit = n match {
      case LiteralNode(true) if useIntForBoolean => b"\(1=1\)"
      case LiteralNode(false) if useIntForBoolean => b"\(1=0\)"
      case LiteralNode(null) => b"null"
      case Library.Not(Library.==(l, LiteralNode(null))) =>
        b"\($l is not null\)"
      case Library.==(l, LiteralNode(null)) =>
        b"\($l is null\)"
      case Library.==(left: ProductNode, right: ProductNode) =>
        b"\("
        if(supportsTuples) b"$left = $right"
        else {
          val cols = left.nodeChildren zip right.nodeChildren
          b.sep(cols, " and "){ case (l,r) => expr(l); b += "="; expr(r) }
        }
        b"\)"
      case ProductNode(ch) =>
        b"\("
        b.sep(ch, ", ")(expr(_))
        b"\)"
      case Library.Exists(c: Comprehension) if(!supportsTuples) =>
        /* If tuples are not supported, selecting multiple individial columns
         * in exists(select ...) is probably not supported, either, so we rewrite
         * such sub-queries to "select *". */
        b"exists(!${c.copy(select = None)})"
      case Library.Concat(l, r) if concatOperator.isDefined =>
        b"\($l${concatOperator.get}$r\)"
      case Library.User() if !capabilities.contains(SqlProfile.capabilities.functionUser) =>
        b += "''"
      case Library.Database() if !capabilities.contains(SqlProfile.capabilities.functionDatabase) =>
        b += "''"
      case Library.Pi() if !hasPiFunction => b += pi
      case Library.Degrees(ch) if !hasRadDegConversion => b"(180.0/!${Library.Pi.typed(typeMapperDelegates.bigDecimalTypeMapperDelegate)}*$ch)"
      case Library.Radians(ch) if!hasRadDegConversion => b"(!${Library.Pi.typed(typeMapperDelegates.bigDecimalTypeMapperDelegate)}/180.0*$ch)"
      case s: SimpleFunction =>
        if(s.scalar) b"{fn "
        b"${s.name}("
        b.sep(s.nodeChildren, ",")(expr(_, true))
        b")"
        if(s.scalar) b += '}'
      case SimpleLiteral(w) => b += w
      case s: SimpleExpression => s.toSQL(this)
      case Library.Between(left, start, end) => b"$left between $start and $end"
      case Library.CountDistinct(e) => b"count(distinct $e)"
      case Library.Like(l, r) => b"\($l like $r\)"
      case Library.Like(l, r, LiteralNode(esc: Char)) =>
        if(esc == '\'' || esc == '%' || esc == '_') throw new SlickException("Illegal escape character '"+esc+"' for LIKE expression")
        // JDBC defines an {escape } syntax but the unescaped version is understood by more DBs/drivers
        b"\($l like $r escape '$esc'\)"
      case Library.StartsWith(n, LiteralNode(s: String)) =>
        b"\($n like ${quote(likeEncode(s)+'%')} escape '^'\)"
      case Library.EndsWith(n, LiteralNode(s: String)) =>
        b"\($n like ${quote("%"+likeEncode(s))} escape '^'\)"
      case Library.Trim(n) =>
        expr(Library.LTrim.typed(typeMapperDelegates.stringTypeMapperDelegate,
          Library.RTrim.typed(typeMapperDelegates.stringTypeMapperDelegate, n)), skipParens)
      case a @ Library.Cast(ch @ _*) =>
        val tn =
          if(ch.length == 2) ch(1).asInstanceOf[LiteralNode].value.asInstanceOf[String]
          else a.asInstanceOf[Typed].tpe.asInstanceOf[TypeMapper[_]].apply(driver).sqlTypeName
        if(supportsCast) b"cast(${ch(0)} as $tn)"
        else b"{fn convert(!${ch(0)},$tn)}"
      case s: SimpleBinaryOperator => b"\(${s.left} ${s.name} ${s.right}\)"
      case Apply(sym: Library.SqlOperator, ch) =>
        b"\("
        if(ch.length == 1) {
          b"${sym.name} ${ch.head}"
        } else b.sep(ch, " " + sym.name + " ")(expr(_))
        b"\)"
      case Apply(sym: Library.JdbcFunction, ch) =>
        b"{fn ${sym.name}("
        b.sep(ch, ",")(expr(_, true))
        b")}"
      case Apply(sym: Library.SqlFunction, ch) =>
        b"${sym.name}("
        b.sep(ch, ",")(expr(_, true))
        b")"
      case l @ LiteralNode(v) => b += typeInfoFor(l.tpe).valueToSQLLiteral(v)
      case c @ BindColumn(v) => b +?= { (p, param) => c.typeMapper(driver).setValue(v, p) }
      case pc @ ParameterColumn(extractor) => b +?= { (p, param) =>
        pc.typeMapper(driver).setValue(extractor.asInstanceOf[(Any => Any)](param), p)
      }
      case c: ConditionalExpr =>
        b"(case"
        c.clauses.reverseIterator.foreach { case IfThen(l, r) => b" when $l then $r" }
        c.elseClause match {
          case LiteralNode(null) =>
          case n => b" else $n"
        }
        b" end)"
      case RowNumber(by) =>
        b"row_number() over("
        if(by.isEmpty) b"order by (select 1)"
        else buildOrderByClause(by)
        b")"
      case Path(field :: (rest @ (_ :: _))) =>
        val struct = rest.reduceRight[Symbol] {
          case (ElementSymbol(idx), z) => joins(z).nodeGenerators(idx-1)._1
        }
        b += symbolName(struct) += '.' += symbolName(field)
      case n => // try to build a sub-query
        b"\("
        buildComprehension(toComprehension(n))
        b"\)"
    }

    protected def buildOrdering(n: Node, o: Ordering) {
      expr(n)
      if(o.direction.desc) b" desc"
      if(o.nulls.first) b" nulls first"
      else if(o.nulls.last) b" nulls last"
    }

    def buildUpdate: QueryBuilderResult = {
      val (gen, from, where, select) = input.ast match {
        case Comprehension(Seq((sym, from: TableNode)), where, None, _, Some(Pure(select)), None, None) => select match {
          case f @ Select(Ref(struct), _) if struct == sym => (sym, from, where, Seq(f.field))
          case ProductNode(ch) if ch.forall{ case Select(Ref(struct), _) if struct == sym => true; case _ => false} =>
            (sym, from, where, ch.map{ case Select(Ref(_), field) => field })
          case _ => throw new SlickException("A query for an UPDATE statement must select table columns only -- Unsupported shape: "+select)
        }
        case o => throw new SlickException("A query for an UPDATE statement must resolve to a comprehension with a single table -- Unsupported shape: "+o)
      }

      val qtn = quoteIdentifier(from.tableName)
      symbolName(gen) = qtn // Alias table to itself because UPDATE does not support aliases
      b"update $qtn set "
      b.sep(select, ", ")(field => b += symbolName(field) += " = ?")
      if(!where.isEmpty) {
        b" where "
        expr(where.reduceLeft((a, b) => Library.And.typed(typeMapperDelegates.booleanTypeMapperDelegate, a, b)), true)
      }
      QueryBuilderResult(b.build, input.linearizer)
    }

    def buildDelete: QueryBuilderResult = {
      val (gen, from, where) = input.ast match {
        case Comprehension(Seq((sym, from: TableNode)), where, _, _, Some(Pure(select)), None, None) => (sym, from, where)
        case o => throw new SlickException("A query for a DELETE statement must resolve to a comprehension with a single table -- Unsupported shape: "+o)
      }
      val qtn = quoteIdentifier(from.tableName)
      symbolName(gen) = qtn // Alias table to itself because DELETE does not support aliases
      b"delete from $qtn"
      if(!where.isEmpty) {
        b" where "
        expr(where.reduceLeft((a, b) => Library.And.typed(typeMapperDelegates.booleanTypeMapperDelegate, a, b)), true)
      }
      QueryBuilderResult(b.build, input.linearizer)
    }
  }

  /** QueryBuilder mix-in for pagination based on RowNumber. */
  trait RowNumberPagination extends QueryBuilder {
    case class StarAnd(child: Node) extends UnaryNode {
      protected[this] def nodeRebuild(child: Node): Node = StarAnd(child)
    }

    override def expr(c: Node, skipParens: Boolean = false): Unit = c match {
      case StarAnd(ch) => b"*, !$ch"
      case _ => super.expr(c, skipParens)
    }

    override protected def buildComprehension(c: Comprehension) {
      if(c.fetch.isDefined || c.offset.isDefined) {
        val r = newSym
        val rn = symbolName(r)
        val tn = symbolName(newSym)
        val c2 = makeSelectPageable(c, r)
        val c3 = Phase.fixRowNumberOrdering.fixRowNumberOrdering(c2, None).asInstanceOf[Comprehension]
        b"select "
        buildSelectModifiers(c)
        c3.select match {
          case Some(Pure(StructNode(ch))) =>
            b.sep(ch.filter { case (_, RowNumber(_)) => false; case _ => true }, ", ") {
              case (sym, StarAnd(RowNumber(_))) => b"*"
              case (sym, _) => b += symbolName(sym)
            }
          case o => throw new SlickException("Unexpected node "+o+" in SELECT slot of "+c)
        }
        b" from ("
        super.buildComprehension(c3)
        b") $tn where $rn"
        (c.fetch, c.offset) match {
          case (Some(t), Some(d)) => b" between ${d+1L} and ${t+d}"
          case (Some(t), None   ) => b" between 1 and $t"
          case (None,    Some(d)) => b" > $d"
          case _ => throw new SlickException("Unexpected empty fetch/offset")
        }
        b" order by $rn"
      }
      else super.buildComprehension(c)
    }

    /** Create aliases for all selected rows (unless it is a "select *" query),
      * add a RowNumber column, and remove FETCH and OFFSET clauses. The SELECT
      * clause of the resulting Comprehension always has the shape
      * Some(Pure(StructNode(_))). */
    protected def makeSelectPageable(c: Comprehension, rn: AnonSymbol): Comprehension = c.select match {
      case Some(Pure(StructNode(ch))) =>
        c.copy(select = Some(Pure(StructNode(ch :+ (rn -> RowNumber())))), fetch = None, offset = None)
      case Some(Pure(ProductNode(ch))) =>
        c.copy(select = Some(Pure(StructNode(ch.toIndexedSeq.map(n => newSym -> n) :+ (rn -> RowNumber())))), fetch = None, offset = None)
      case Some(Pure(n)) =>
        c.copy(select = Some(Pure(StructNode(IndexedSeq(newSym -> n, rn -> RowNumber())))), fetch = None, offset = None)
      case None =>
        // should not happen at the outermost layer, so copying an extra row does not matter
        c.copy(select = Some(Pure(StructNode(IndexedSeq(rn -> StarAnd(RowNumber()))))), fetch = None, offset = None)
    }
  }

  /** QueryBuilder mix-in for Oracle-style ROWNUM (applied before ORDER BY
    * and GROUP BY) instead of the standard SQL ROWNUMBER(). */
  trait OracleStyleRowNum extends QueryBuilder {
    override protected def toComprehension(n: Node, liftExpression: Boolean = false) =
      super.toComprehension(n, liftExpression) match {
        case c @ Comprehension(from, _, None, orderBy, Some(sel), _, _) if !orderBy.isEmpty && hasRowNumber(sel) =>
          // Pull the SELECT clause with the ROWNUM up into a new query
          val paths = findPaths(from.map(_._1).toSet, sel).map(p => (p, new AnonSymbol)).toMap
          val inner = c.copy(select = Some(Pure(StructNode(paths.toIndexedSeq.map { case (n,s) => (s,n) }))))
          val gen = new AnonSymbol
          val newSel = sel.replace {
            case s: Select => paths.get(s).fold(s) { sym => Select(Ref(gen), sym) }
          }
          Comprehension(Seq((gen, inner)), select = Some(newSel))
        case c => c
      }

    override def expr(n: Node, skipParens: Boolean = false) = n match {
      case RowNumber(_) => b"rownum"
      case _ => super.expr(n, skipParens)
    }
  }

  /** Builder for INSERT statements. */
  class InsertBuilder(val node: Node) {

    class PartsResult(val table: String, val fields: IndexedSeq[FieldSymbol]) {
      def qtable = quoteIdentifier(table)
      def qcolumns = fields.map(s => quoteIdentifier(s.name)).mkString(",")
      def qvalues = IndexedSeq.fill(fields.size)("?").mkString(",")
    }

    def buildInsert: InsertBuilderResult = {
      val pr = buildParts(node)
      InsertBuilderResult(pr.table, s"INSERT INTO ${pr.qtable} (${pr.qcolumns}) VALUES (${pr.qvalues})")
    }

    def buildInsert(query: Query[_, _]): InsertBuilderResult = {
      val pr = buildParts(node)
      val qb = driver.createQueryBuilder(query)
      qb.sqlBuilder += s"INSERT INTO ${pr.qtable} (${pr.qcolumns}) "
      val qbr = qb.buildSelect()
      InsertBuilderResult(pr.table, qbr.sql, qbr.setter)
    }

    def buildReturnColumns(node: Node, table: String): IndexedSeq[FieldSymbol] = {
      val r = buildParts(node)
      if(r.table != table)
        throw new SlickException("Returned key columns must be from same table as inserted columns ("+
          r.table+" != "+table+")")
      if(!capabilities.contains(JdbcProfile.capabilities.returnInsertOther) && (r.fields.size > 1 || !r.fields.head.options.contains(ColumnOption.AutoInc)))
        throw new SlickException("This DBMS allows only a single AutoInc column to be returned from an INSERT")
      r.fields
    }

    protected def buildParts(node: Node): PartsResult = {
      val cols = new ArrayBuffer[FieldSymbol]
      var table: String = null
      def f(c: Any): Unit = c match {
        case ProductNode(ch) => ch.foreach(f)
        case t:TableNode => f(Node(t.nodeShaped_*.value))
        case Select(Ref(IntrinsicSymbol(t: TableNode)), field: FieldSymbol) =>
          if(table eq null) table = t.tableName
          else if(table != t.tableName) throw new SlickException("Inserts must all be to the same table")
          cols += field
        case _ => throw new SlickException("Cannot use column "+c+" in INSERT statement")
      }
      f(node)
      if(table eq null) throw new SlickException("No table to insert into")
      new PartsResult(table, cols)
    }
  }

  /** Builder for various DDL statements. */
  class TableDDLBuilder(val table: Table[_]) { self =>
    protected val columns: Iterable[ColumnDDLBuilder] = table.create_*.map(fs => createColumnDDLBuilder(fs, table))
    protected val indexes: Iterable[Index] = table.indexes
    protected val foreignKeys: Iterable[ForeignKey[_ <: TableNode, _]] = table.foreignKeys
    protected val primaryKeys: Iterable[PrimaryKey] = table.primaryKeys

    def buildDDL: DDL = {
      if(primaryKeys.size > 1)
        throw new SlickException("Table "+table.tableName+" defines multiple primary keys ("
          + primaryKeys.map(_.name).mkString(", ") + ")")
      DDL(createPhase1, createPhase2, dropPhase1, dropPhase2)
    }

    protected def createPhase1 = Iterable(createTable) ++ primaryKeys.map(createPrimaryKey) ++ indexes.map(createIndex)
    protected def createPhase2 = foreignKeys.map(createForeignKey)
    protected def dropPhase1 = foreignKeys.map(dropForeignKey)
    protected def dropPhase2 = primaryKeys.map(dropPrimaryKey) ++ Iterable(dropTable)

    protected def createTable: String = {
      val b = new StringBuilder append "create table " append quoteIdentifier(table.tableName) append " ("
      var first = true
      for(c <- columns) {
        if(first) first = false else b append ","
        c.appendColumn(b)
      }
      addTableOptions(b)
      b append ")"
      b.toString
    }

    protected def addTableOptions(b: StringBuilder) {}

    protected def dropTable: String = "drop table "+quoteIdentifier(table.tableName)

    protected def createIndex(idx: Index): String = {
      val b = new StringBuilder append "create "
      if(idx.unique) b append "unique "
      b append "index " append quoteIdentifier(idx.name) append " on " append quoteIdentifier(table.tableName) append " ("
      addIndexColumnList(idx.on, b, idx.table.tableName)
      b append ")"
      b.toString
    }

    protected def createForeignKey(fk: ForeignKey[_ <: TableNode, _]): String = {
      val sb = new StringBuilder append "alter table " append quoteIdentifier(table.tableName) append " add "
      addForeignKey(fk, sb)
      sb.toString
    }

    protected def addForeignKey(fk: ForeignKey[_ <: TableNode, _], sb: StringBuilder) {
      sb append "constraint " append quoteIdentifier(fk.name) append " foreign key("
      addForeignKeyColumnList(fk.linearizedSourceColumns, sb, table.tableName)
      sb append ") references " append quoteIdentifier(fk.targetTable.tableName) append "("
      addForeignKeyColumnList(fk.linearizedTargetColumnsForOriginalTargetTable, sb, fk.targetTable.tableName)
      sb append ") on update " append fk.onUpdate.action
      sb append " on delete " append fk.onDelete.action
    }

    protected def createPrimaryKey(pk: PrimaryKey): String = {
      val sb = new StringBuilder append "alter table " append quoteIdentifier(table.tableName) append " add "
      addPrimaryKey(pk, sb)
      sb.toString
    }

    protected def addPrimaryKey(pk: PrimaryKey, sb: StringBuilder) {
      sb append "constraint " append quoteIdentifier(pk.name) append " primary key("
      addPrimaryKeyColumnList(pk.columns, sb, table.tableName)
      sb append ")"
    }

    protected def dropForeignKey(fk: ForeignKey[_ <: TableNode, _]): String =
      "alter table " + quoteIdentifier(table.tableName) + " drop constraint " + quoteIdentifier(fk.name)

    protected def dropPrimaryKey(pk: PrimaryKey): String =
      "alter table " + quoteIdentifier(table.tableName) + " drop constraint " + quoteIdentifier(pk.name)

    protected def addIndexColumnList(columns: IndexedSeq[Node], sb: StringBuilder, requiredTableName: String) =
      addColumnList(columns, sb, requiredTableName, "index")

    protected def addForeignKeyColumnList(columns: IndexedSeq[Node], sb: StringBuilder, requiredTableName: String) =
      addColumnList(columns, sb, requiredTableName, "foreign key constraint")

    protected def addPrimaryKeyColumnList(columns: IndexedSeq[Node], sb: StringBuilder, requiredTableName: String) =
      addColumnList(columns, sb, requiredTableName, "foreign key constraint")

    protected def addColumnList(columns: IndexedSeq[Node], sb: StringBuilder, requiredTableName: String, typeInfo: String) {
      var first = true
      for(c <- columns) c match {
        case Select(Ref(IntrinsicSymbol(t: TableNode)), field: FieldSymbol) =>
          if(first) first = false
          else sb append ","
          sb append quoteIdentifier(field.name)
          if(requiredTableName != t.tableName)
            throw new SlickException("All columns in "+typeInfo+" must belong to table "+requiredTableName)
        case _ => throw new SlickException("Cannot use column "+c+" in "+typeInfo+" (only named columns are allowed)")
      }
    }
  }

  /** Builder for column specifications in DDL statements. */
  class ColumnDDLBuilder(column: FieldSymbol) {
    protected val tmDelegate = typeInfoFor(column.tpe)
    protected var sqlType: String = null
    protected var notNull = !tmDelegate.nullable
    protected var autoIncrement = false
    protected var primaryKey = false
    protected var defaultLiteral: String = null
    init()

    protected def init() {
      for(o <- column.options) handleColumnOption(o)
      if(sqlType eq null) sqlType = tmDelegate.sqlTypeName
    }

    protected def handleColumnOption(o: ColumnOption[_]): Unit = o match {
      case ColumnOption.DBType(s) => sqlType = s
      case ColumnOption.NotNull => notNull = true
      case ColumnOption.Nullable => notNull = false
      case ColumnOption.AutoInc => autoIncrement = true
      case ColumnOption.PrimaryKey => primaryKey = true
      case ColumnOption.Default(v) => defaultLiteral = typeInfoFor(column.tpe).valueToSQLLiteral(v)
    }

    def appendColumn(sb: StringBuilder) {
      sb append quoteIdentifier(column.name) append ' '
      sb append sqlType
      appendOptions(sb)
    }

    protected def appendOptions(sb: StringBuilder) {
      if(defaultLiteral ne null) sb append " DEFAULT " append defaultLiteral
      if(autoIncrement) sb append " GENERATED BY DEFAULT AS IDENTITY(START WITH 1)"
      if(notNull) sb append " NOT NULL"
      if(primaryKey) sb append " PRIMARY KEY"
    }
  }

  /** Builder for DDL statements for sequences. */
  class SequenceDDLBuilder(seq: Sequence[_]) {
    def buildDDL: DDL = {
      val b = new StringBuilder append "create sequence " append quoteIdentifier(seq.name)
      seq._increment.foreach { b append " increment " append _ }
      seq._minValue.foreach { b append " minvalue " append _ }
      seq._maxValue.foreach { b append " maxvalue " append _ }
      seq._start.foreach { b append " start " append _ }
      if(seq._cycle) b append " cycle"
      DDL(b.toString, "drop sequence " + quoteIdentifier(seq.name))
    }
  }
}

case class QueryBuilderInput(state: CompilationState, linearizer: ValueLinearizer[_]) {
  def ast = state.tree
}

case class QueryBuilderResult(sbr: SQLBuilder.Result, linearizer: ValueLinearizer[_]) {
  def sql = sbr.sql
  def setter = sbr.setter
}

case class InsertBuilderResult(table: String, sql: String, setter: SQLBuilder.Setter = SQLBuilder.EmptySetter)
