package com.andreimikhailov.formats

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.http4k.core.Body
import org.http4k.format.JacksonYaml.auto
import java.nio.file.Files
import java.nio.file.Path

data class YamlConfigCommon(
    val proto : String
    , val site  : String
    , val dbURL : String
    , val dbLogin : String
    , val dbPassword : String
)
val yamlConfigCommonLens = Body.auto<YamlConfigCommon>().toLens()
fun loadConfCommonFromFile(path: Path): YamlConfigCommon {
    val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule() // Enable YAML parsing
    return Files.newBufferedReader(path).use {
        mapper.readValue(it, YamlConfigCommon::class.java)
    }
}
data class YamlConfigInstance(
    val remotePort : Int
    , val urlPath : String
    , val localPort  : Int
    , val tableOfUsers : String
    , val tableOfEvents: String
    , val links: Map<String,String>
    , val top: String?
    , val staticDir: String
    , val zoneId: String
)
val yamlConfigInstanceLens = Body.auto<YamlConfigInstance>().toLens()
fun loadConfInstanceFromFile(path: Path): YamlConfigInstance {
    val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule() // Enable YAML parsing
    return Files.newBufferedReader(path).use {
        mapper.readValue(it, YamlConfigInstance::class.java)
    }
}