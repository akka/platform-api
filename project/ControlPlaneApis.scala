import sbt.*
import Keys.*
import play.api.libs.json.{ JsArray, JsObject, JsString, JsValue, Json }

object ControlPlaneApis extends AutoPlugin {

  override def trigger = PluginTrigger.NoTrigger

  object autoImport {
    val controlPlaneApiVersion = settingKey[String]("the version of the control plane API")
    val controlPlaneApiKubectlContext = settingKey[Option[String]]("the kubectl context to use to fetch control plane APIs, none if default context")
    val controlPlaneApiVersionGroups = settingKey[Seq[(String, String)]]("version groups to include in the schema")
    val controlPlaneApiExcludeResourceVersions = settingKey[Seq[(String, String, String, String)]]("resource versions to exclude")
    val controlPlaneApiRenameSchema = settingKey[Map[String, String]]("schema components to rename")

    val controlPlaneApiFetchSchemas = taskKey[Seq[JsValue]]("fetch schemas for the control plane API")
    val controlPlaneApiSchema = taskKey[File]("produce the final control plane API schema")
  }

  import autoImport.*
  import sys.process.*

  override def projectSettings: Seq[Def.Setting[?]] = Seq(
    controlPlaneApiVersion := "v1",
    controlPlaneApiKubectlContext := sys.env.get("KUBECTL_CONTEXT"),
    controlPlaneApiVersionGroups := Seq(
      "kalix.io" -> "v1alpha1",
      "kalix.io" -> "v1beta1"
    ),
    controlPlaneApiExcludeResourceVersions := Seq(
      ("kalix.io", "v1alpha1", "kalixobservabilities", "KalixObservability")
    ),
    controlPlaneApiRenameSchema := Map(
      "ServiceStatusResourceSyncStatusRegion" -> "ResourceSyncStatusRegion",
      "ObservabilitySpecLogOtlpHttpHeaderValueFromSecretKeyRef" -> "SecretKeyRef",
      "RouteStatusCondition" -> "Condition",
      "ServiceStatusCondition" -> "ServiceCondition",
      "ServiceSpecResourceSync" -> "ResourceSync",
      "ServiceStatusResourceSyncStatus" -> "ResourceSyncStatus",
    ),
    controlPlaneApiFetchSchemas := {
      val versionGroups = controlPlaneApiVersionGroups.value
      val kubectlContext = controlPlaneApiKubectlContext.value

      val contextArgs = kubectlContext.map(c => Seq("--context", c)).getOrElse(Nil)

      versionGroups.map {
        case (group, version) =>
          val schema = (Seq("kubectl", "get", "--raw", s"/openapi/v3/apis/$group/$version") ++ contextArgs).!!
          Json.parse(schema)
      }
    },
    controlPlaneApiSchema / target := baseDirectory.value / "schemas" / "control-plane" / "openapi" / "api.json",
    controlPlaneApiSchema := {
      val dest = (controlPlaneApiSchema / target).value
      val json = transformSchemas(controlPlaneApiFetchSchemas.value, controlPlaneApiVersion.value, controlPlaneApiExcludeResourceVersions.value, controlPlaneApiRenameSchema.value)
      IO.write(dest, Json.prettyPrint(json))
      dest
    }
  )

  private def transformSchemas(schemas: Seq[JsValue], version: String, excludeResourceVersions: Seq[(String, String, String, String)], configuredRenames: Map[String, String]): JsValue = {
    // Paths
    val paths = schemas
      .map(s => (s \ "paths").as[JsObject])
      .reduce(_ ++ _)
      .value
      .toSeq
      .filter {
        case (path, _) => includePath(path, excludeResourceVersions.map(t => (t._1, t._2, t._3)).toSet)
      }.map {
        case (path, spec: JsObject) =>
          // Rename namespace parameter
          val newPath = path.replace("{namespace}", "{projectId}")
          newPath -> JsObject(spec.value.map {
            case ("parameters", params) =>
              "parameters" -> JsArray(params.as[Seq[JsObject]]
                .flatMap { param =>
                  val name = (param \ "name").as[String]
                  if (name == "namespace") {
                    Seq(Json.obj(
                      "name" -> "projectId",
                      "in" -> "path",
                      "description" -> "the id of the Akka project",
                      "required" -> true,
                      "schema" -> Json.obj(
                        "type" -> "string",
                        "uniqueItems" -> true
                      )
                    ))
                  } else if (name == "name") {
                    val description = (param \ "description").as[String].replaceAll("Kalix", "")
                    Seq(param + ("description" -> JsString(description)))
                  } else if (name == "pretty") Nil
                  else Seq(param)
                })
            case (method, op: JsObject) =>
              method -> transformOperation(op)
          })
      }.sortBy(_._1)

    val componentPrefixToExclude = excludeResourceVersions.map {
      case (g, v, _, r) =>
        // reverse group
        val group = g.split("\\.").reverse.mkString(".")
        s"$group.$v.$r"
    } ++ Seq("io.k8s.api.autoscaling.")

    // Component schemas
    val componentSchemas = schemas.map(s => (s \ "components" \ "schemas").as[JsObject])
      .reduce(_ ++ _)
      .value
      .toSeq
      .filterNot {
        case (name, _) =>
          componentPrefixToExclude.exists(prefix => name.startsWith(prefix))
      }
      .map {
        case (name, spec) =>
          (name, renameComponent(name), spec)
      }.sortBy(_._1)

    // Verify that there are no components with the same name
    componentSchemas.groupBy(_._2)
      .foreach {
        case (name, values) =>
          if (values.size > 1) {
            sys.error(s"There are multiple different components with name $name: ${values.map(_._1)}")
          }
      }

    val schemaRefsRenamed = componentSchemas.map(cs => cs._2 -> cs._3).map {
      case (name, obj) => name -> renameRefs(obj).as[JsObject]
    }.toMap
    val (normalizedSchemas, renameMap) = normalizeInlineSchemas(schemaRefsRenamed, configuredRenames)

    val finalPaths = applyRenameMap(renameRefs(JsObject(paths)), renameMap)
    val fixedSchemas = normalizedSchemas.map {
      case (name, obj) => name -> fixEmptyObjectDefaults(obj)
    }

    Json.obj(
      "openapi" -> "3.0.0",
      "info" -> Json.obj(
        "title" -> "Akka Control Plane API",
        "version" -> version
      ),
      "paths" -> finalPaths,
      "components" -> Json.obj(
        "schemas" -> fixedSchemas
      )
    )
  }

  private val ignoreParams = Set(
    "dryRun",
    "fieldManager",
    "fieldValidation",
    "gracePeriodSeconds",
    "ignoreStoreReadErrorWithClusterBreakingPotential",
    "orphanDependents",
    "propagationPolicy",
    "allowWatchBookmarks",
    "fieldSelector",
    "resourceVersion",
    "resourceVersionMatch",
    "sendInitialEvents",
    "watch",
    "continue",
    "timeoutSeconds",
    "limit",
    "force"
  )

  private val MergePatchContentType = "application/merge-patch+json"

  // The OpenAPI generator uses the first request body content type as the request's
  // Content-Type. Put merge-patch first so generated patch operations use it:
  // apply-patch requires a fieldManager parameter that is not modelled in this schema.
  private def preferMergePatch(spec: JsObject): JsObject =
    (spec \ "requestBody" \ "content").asOpt[JsObject] match {
      case Some(content) if content.value.contains(MergePatchContentType) =>
        val reordered = JsObject(
          Seq(MergePatchContentType -> content(MergePatchContentType)) ++
            content.value.toSeq.filterNot(_._1 == MergePatchContentType))
        spec + ("requestBody" -> ((spec \ "requestBody").as[JsObject] + ("content" -> reordered)))
      case _ => spec
    }

  private def transformOperation(spec: JsObject): JsObject = {
    // Rename operation id
    val operationId = renameOperationId((spec \ "operationId").as[String])
    // filter params
    val parameters = (spec \ "parameters")
      .as[Seq[JsObject]]
      .filterNot { param =>
        val name = (param \ "name").as[String]
        ignoreParams.contains(name)
      }
    val description = (spec \ "description").as[String].replaceAll("Kalix", "")

    preferMergePatch(spec) ++
      Json.obj(
        "operationId" -> operationId,
        "description" -> description,
        "parameters" -> parameters,
        // This is what the open api generator uses to name the client class
        "tags" -> Seq("AkkaControlPlane"),
      )
  }

  private def renameRefs(v: JsValue): JsValue = {
    v match {
      case o: JsObject =>
        JsObject(o.value.toSeq.map {
          case ("$ref", ref) =>
            "$ref" -> JsString(renameRef(ref.as[String]))
          case (other, value) =>
            other -> renameRefs(value)
        })
      case a: JsArray =>
        JsArray(a.value.map(renameRefs))
      case other => other
    }
  }

  private def renameRefsInContentParam(spec: JsObject): JsObject = {
    (spec \ "content").asOpt[JsObject].map { content =>
      val contentFields = content.value.map {
        case (ct, value) =>
          val ref = renameRef((value \ "schema" \ "$ref").as[String])
          ct -> Json.obj(
            "schema" -> Json.obj(
              "$ref" -> ref
            )
          )
      }
      spec + ("content" -> JsObject(contentFields))
    }.getOrElse(spec)
  }

  private def includePath(path: String, excludeResourceVersions: Set[(String, String, String)]): Boolean = {
    val parts = path.split("/")

    // Example path: "/apis/kalix.io/v1alpha1/namespaces/{namespace}/containerregistryconfigs/{name}"
    // Remember that there's an empty string before leading / that comes from split.
    // First, exclude anything that has less than 7 parts
    if (parts.length < 7) {
      false
      // And just in case, exclude all non namespaced resources
    } else if (parts(4) != "namespaces") {
      false
      // Exclude status/scale sub resources
    } else if (parts.length > 8) {
      false
    } else {
      val group = parts(2)
      val version = parts(3)
      val resource = parts(6)
      !excludeResourceVersions.contains((group, version, resource))
    }
  }

  // Fixes https://github.com/OpenAPITools/openapi-generator/issues/19391 by removing the default
  private def fixEmptyObjectDefaults(schema: JsObject): JsObject = {
    (schema \ "properties").asOpt[JsObject] match {
      case Some(properties) =>
        val fixedProperties = JsObject(properties.value.map {
          case (name, value: JsObject) =>
            val fixed = (value \ "default").asOpt[JsValue] match {
              case Some(obj: JsObject) if obj.value.isEmpty => value - "default"
              case _ => value
            }
            name -> fixed
        })
        schema + ("properties" -> fixedProperties)
      case None =>
        schema
    }
  }


  // Phase 1 + 2: extract all nested inline object schemas to top-level names, then merge
  // identical schemas under a shared name derived from their longest common CamelCase suffix.
  // Returns the normalized schema map and a rename map (old → canonical) to apply to all $refs
  // in the full document (paths + components) via applyRenameMap.
  // Precondition: all $refs use the form "#/components/schemas/Name".
  private def normalizeInlineSchemas(schemas: Map[String, JsObject], configuredRenames: Map[String, String]): (Map[String, JsObject], Map[String, String]) = {
    @annotation.tailrec
    def loop(schemas: Map[String, JsObject], renameMapSoFar: Map[String, String]): (Map[String, JsObject], Map[String, String]) = {
      val (deduplicated, renameMap) = deduplicateSchemas(schemas, configuredRenames)
      if (renameMap.isEmpty) {
        (schemas, renameMapSoFar)
      } else {
        val allRenames = renameMap ++ renameMapSoFar
        val renamed = deduplicated.map {
          case (name, obj) => name -> applyRenameMap(obj, allRenames).as[JsObject]
        }
        loop(renamed, allRenames)
      }
    }

    val withExtracted = extractNestedObjects(schemas)
    loop(withExtracted, configuredRenames)
  }

  private def extractNestedObjects(schemas: Map[String, JsObject]): Map[String, JsObject] = {
    @annotation.tailrec
    def loop(pending: Set[String], current: Map[String, JsObject]): Map[String, JsObject] =
      if (pending.isEmpty) current
      else {
        val name = pending.head
        val (updated, extracted) = extractNestedFromSchema(name, current(name), current.keySet)
        val fresh = extracted.filterNot { case (n, _) => current.contains(n) }
        loop(pending.tail ++ fresh.keySet, current.updated(name, updated) ++ fresh)
      }

    loop(schemas.keySet, schemas)
  }

  private def extractNestedFromSchema(
                                       parentName: String,
                                       schema: JsObject,
                                       existingNames: Set[String]
                                     ): (JsObject, Map[String, JsObject]) = {
    var extracted = Map.empty[String, JsObject]

    def maybeExtract(childName: String, childSchema: JsObject): JsObject =
      if (isInlineObject(childSchema) && !existingNames.contains(childName) && !extracted.contains(childName)) {
        extracted += childName -> childSchema
        Json.obj("$ref" -> s"#/components/schemas/$childName")
      } else childSchema

    def processProperty(propName: String, propSchema: JsObject): JsObject = {
      val pascal = propName.head.toUpper + propName.tail
      (propSchema \ "type").asOpt[String] match {
        case Some("array") =>
          (propSchema \ "items").asOpt[JsObject] match {
            case Some(items) =>
              val itemName = singularizePropertyName(propName)
                .map(s => parentName + s.head.toUpper + s.tail)
                .getOrElse(parentName + pascal)
              propSchema + ("items" -> maybeExtract(itemName, items))
            case None => propSchema
          }
        case _ =>
          maybeExtract(parentName + pascal, propSchema)
      }
    }

    val afterProps = (schema \ "properties").asOpt[JsObject].fold(schema) { props =>
      schema + ("properties" -> JsObject(props.value.toSeq.map {
        case (pName, pSchema: JsObject) => pName -> processProperty(pName, pSchema)
        case pair => pair
      }))
    }

    val result = (afterProps \ "additionalProperties").asOpt[JsObject].fold(afterProps) { addl =>
      afterProps + ("additionalProperties" -> maybeExtract(parentName + "Value", addl))
    }

    (result, extracted)
  }

  private def isInlineObject(schema: JsObject): Boolean =
    (schema \ "$ref").asOpt[JsValue].isEmpty &&
      ((schema \ "type").asOpt[String].contains("object") || (schema \ "properties").asOpt[JsValue].isDefined)

  // Only attempts singularization for clearly plural forms; returns None rather than guessing.
  private def singularizePropertyName(name: String): Option[String] =
    if (name.endsWith("ies") && name.length > 4)
      Some(name.dropRight(3) + "y")
    else if (name.endsWith("ses") || name.endsWith("xes") || name.endsWith("ches") || name.endsWith("shes"))
      Some(name.dropRight(2))
    else if (name.endsWith("s") && !name.endsWith("ss") && !name.endsWith("us") && !name.endsWith("is") && name.length > 2)
      Some(name.dropRight(1))
    else
      None

  private def deduplicateSchemas(schemas: Map[String, JsObject], configuredRenames: Map[String, String]): (Map[String, JsObject], Map[String, String]) = {
    val grouped = schemas.toSeq.groupBy(_._2 - "description")
    grouped.values
      .foldLeft((Map.empty[String, JsObject], Map.empty[String, String])) {
        case ((result, renames), Seq((name, obj))) =>
          ((result + (renames.getOrElse(name, name) -> obj)), renames)
        case ((result, renames), group) =>
          val names = group.map(_._1).sorted
          val schema = group.head._2
          // First see if any of the names are in the rename map
          val configuredRename = names.collectFirst {
            case name if configuredRenames.contains(name) => configuredRenames(name)
          }
          val candidate = configuredRename.getOrElse(longestCommonCamelSuffix(names))
          // Fall back to shortest original name if the candidate conflicts with a schema outside this group
          val canonical =
            if (!names.contains(candidate) && (schemas.contains(candidate) || result.contains(candidate)))
              names.minBy(_.length)
            else candidate
          val newRenames = names.filterNot(_ == canonical).map(_ -> canonical).toMap
          (result + (canonical -> schema), renames ++ newRenames)
      }
  }

  private def longestCommonCamelSuffix(names: Seq[String]): String = {
    val parts = names.map(splitCamelCase)
    val minLen = parts.map(_.length).min
    val n = (minLen to 1 by -1).find(n => parts.map(_.takeRight(n)).distinct.size == 1).getOrElse(0)
    if (n > 0) parts.head.takeRight(n).mkString else names.minBy(_.length)
  }

  private def splitCamelCase(name: String): List[String] =
    name.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])").toList.filter(_.nonEmpty)

  private def applyRenameMap(value: JsValue, renameMap: Map[String, String]): JsValue =
    if (renameMap.isEmpty) value
    else value match {
      case o: JsObject =>
        JsObject(o.value.toSeq.map {
          case ("$ref", ref: JsString) if ref.value.startsWith("#/components/schemas/") =>
            val old = ref.value.stripPrefix("#/components/schemas/")
            "$ref" -> JsString(s"#/components/schemas/${renameMap.getOrElse(old, old)}")
          case (k, v) => k -> applyRenameMap(v, renameMap)
        })
      case a: JsArray => JsArray(a.value.map(applyRenameMap(_, renameMap)))
      case other => other
    }

  private val ListOperation = "list.*?Namespaced(?:Kalix)?(.*)".r
  private val CreateOperation = "create.*?Namespaced(?:Kalix)?(.*)".r
  private val DeleteCollectionOperation = "delete.*?CollectionNamespaced(?:Kalix)?(.*)".r
  private val ReadOperation = "read.*?Namespaced(?:Kalix)?(.*)".r
  private val ReplaceOperation = "replace.*?Namespaced(?:Kalix)?(.*)".r
  private val DeleteOperation = "delete.*?Namespaced(?:Kalix)?(.*)".r
  private val PatchOperation = "patch.*?Namespaced(?:Kalix)?(.*)".r

  private def renameOperationId(operationId: String): String = operationId match {
    case ListOperation(resource) => s"list${pluralize(resource)}"
    case CreateOperation(resource) => s"create$resource"
    case DeleteCollectionOperation(resource) => s"delete${pluralize(resource)}"
    case ReadOperation(resource) => s"get$resource"
    case ReplaceOperation(resource) => s"put$resource"
    case DeleteOperation(resource) => s"delete$resource"
    case PatchOperation(resource) => s"patch$resource"
    case other => sys.error(s"Unrecognized operation id: $other")
  }

  private def pluralize(word: String) = {
    // Very crude, but works for what we've got
    if (word.endsWith("y")) {
      word.replaceAll("y$", "ies")
    } else word + "s"
  }

  private def renameRef(ref: String): String = ref match {
    case r if r.startsWith("#/components/schemas/") =>
      val name = renameComponent(r.stripPrefix("#/components/schemas/"))
      s"#/components/schemas/$name"
    case _ => sys.error(s"Unrecognized schema: $ref")
  }

  private def renameComponent(component: String): String = {
    component.split("\\.").last.replaceAll("Kalix", "")
  }

}
