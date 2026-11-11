import io.circe.{Json, JsonObject}
import io.circe.parser.parse

// Records on every operation, as the `x-security-schemes` vendor extension, the
// names of the security schemes it accepts. Runs before openapi-generator-cli
// sees the spec.
//
// openapi-generator only exposes the schemes it understands (`authMethods`):
// X's `OAuth2UserToken` (oauth2) and `UserToken` (`http` with the non-standard
// scheme `OAuth`, i.e. OAuth 1.0a) are dropped or mangled. The hand-written
// client needs the exact list to pick an authentication method per request
// (see templates/api.mustache, which turns it into a request tag), so it's
// copied verbatim into a vendor extension the generator passes through.
object SecuritySpecFix {

  private val HttpMethods =
    Set("get", "put", "post", "delete", "options", "head", "patch", "trace")

  def fix(rawSpecJson: String): String = {
    val json = parse(rawSpecJson).fold(
      err => sys.error(s"SecuritySpecFix: failed to parse spec JSON: ${err.getMessage}"),
      identity,
    )
    val root = json.asObject.getOrElse(sys.error("SecuritySpecFix: spec is not an object"))
    val globalSecurity = root("security")
    val paths = root("paths").flatMap(_.asObject).getOrElse(JsonObject.empty)
    val fixedPaths = paths.mapValues(pathItem =>
      pathItem.mapObject(_.toList.foldLeft(JsonObject.empty) {
        case (acc, (method, op)) if HttpMethods.contains(method) =>
          acc.add(method, op.mapObject(withSchemes(_, globalSecurity)))
        case (acc, (key, value)) => acc.add(key, value)
      }),
    )
    Json.fromJsonObject(root.add("paths", Json.fromJsonObject(fixedPaths))).spaces2
  }

  private def withSchemes(op: JsonObject, globalSecurity: Option[Json]): JsonObject = {
    val requirements = op("security").orElse(globalSecurity).flatMap(_.asArray).getOrElse(Vector.empty)
    val schemes = requirements.flatMap(_.asObject.toVector.flatMap(_.keys)).distinct
    op.add("x-security-schemes", Json.fromValues(schemes.map(Json.fromString)))
  }
}
