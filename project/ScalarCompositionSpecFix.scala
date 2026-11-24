import io.circe.{Json, JsonObject}
import io.circe.parser.parse

// Collapses an `anyOf`/`oneOf` whose members are all inline scalar schemas into one plain schema.
// Runs before openapi-generator-cli sees the spec.
//
// The X spec writes "a string, in one of two encodings" or "a number, or the same number as text" that
// way, e.g. the file segment of a chunked upload:
//
//   "media": { "anyOf": [ {"type": "string", "format": "binary"}, {"type": "string", "format": "byte"} ] }
//
// openapi-generator turns such a property into a model of its own with no fields
// (`case class AppendMediaUploadRequestMedia()`), which encodes as `{}`: the value cannot be sent at all,
// and a real answer does not decode. A union of scalars has no fields to model, so it becomes:
//   - one member: that member (its `enum` and `format` kept);
//   - several members of the same type: that type (the `format` kept only if they agree, so the upload
//     segment is a `String`, the base64 text a JSON body carries);
//   - members of different types (`integer` or `string`, ...): untyped, which typeMappings turns into
//     `io.circe.Json`, the only type that holds either.
// Keywords next to the composition, such as `description`, are kept.
object ScalarCompositionSpecFix {

  private val Keywords = Seq("anyOf", "oneOf")
  private val ScalarTypes = Set("string", "integer", "number", "boolean")

  def fix(rawSpecJson: String): String = {
    val json = parse(rawSpecJson).fold(
      err => sys.error(s"ScalarCompositionSpecFix: failed to parse spec JSON: ${err.getMessage}"),
      identity,
    )
    walk(json).spaces2
  }

  private def scalarType(member: Json): Option[String] =
    member.asObject
      .filterNot(_.contains("$ref"))
      .flatMap(_("type"))
      .flatMap(_.asString)
      .filter(ScalarTypes.contains)

  private def collapse(obj: JsonObject, keyword: String): JsonObject =
    obj(keyword).flatMap(_.asArray) match {
      case Some(members) if members.nonEmpty && members.forall(scalarType(_).isDefined) =>
        val rest = obj.remove(keyword)
        val memberObjects = members.flatMap(_.asObject)
        val types = members.flatMap(scalarType).distinct
        if (memberObjects.size == 1)
          // What the schema says itself (its description) wins over the member's.
          JsonObject.fromIterable(memberObjects.head.toIterable ++ rest.toIterable)
        else if (types.size == 1) {
          val typed = rest.add("type", Json.fromString(types.head))
          memberObjects.map(_("format")).distinct match {
            case Vector(Some(format)) => typed.add("format", format)
            case _                    => typed
          }
        }
        else rest
      case _ => obj
    }

  private def walk(j: Json): Json =
    j.fold(
      jsonNull = j,
      jsonBoolean = _ => j,
      jsonNumber = _ => j,
      jsonString = _ => j,
      jsonArray = items => Json.fromValues(items.map(walk)),
      jsonObject = obj => Json.fromJsonObject(Keywords.foldLeft(obj)(collapse).mapValues(walk)),
    )
}
