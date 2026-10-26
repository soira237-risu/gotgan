package com.quickfiles.app.data

import com.quickfiles.app.storage.*
import org.json.JSONObject

fun StorageLocation.encode(): String = JSONObject().put("source", sourceId).put("path", path).toString()
fun decodeLocation(value: String): StorageLocation = JSONObject(value).let { StorageLocation(it.getString("source"), it.getString("path")) }
fun Revision.encode(): String = JSONObject().put("size", size ?: JSONObject.NULL).put("modified", modified ?: JSONObject.NULL).put("etag", etag ?: JSONObject.NULL).toString()
fun decodeRevision(value: String): Revision = JSONObject(value).let { Revision(if (it.isNull("size")) null else it.getLong("size"), if (it.isNull("modified")) null else it.getLong("modified"), if (it.isNull("etag")) null else it.getString("etag")) }
