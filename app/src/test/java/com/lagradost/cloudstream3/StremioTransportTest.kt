package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.stremio.StremioAddon
import com.lagradost.cloudstream3.stremio.StremioApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioTransportTest {
    private fun addon(transportUrl: String): StremioAddon {
        val manifest = JSONObject()
            .put("id", "org.test")
            .put("name", "Test")
            .put("types", org.json.JSONArray(listOf("movie", "series")))
            .put("resources", org.json.JSONArray(listOf("catalog", "meta", "stream")))
            .put(
                "catalogs",
                org.json.JSONArray(
                    listOf(
                        JSONObject()
                            .put("id", "top")
                            .put("type", "movie")
                            .put("extra", org.json.JSONArray(listOf("search", "skip")))
                    )
                )
            )
        val descriptor = JSONObject()
            .put("transportUrl", transportUrl)
            .put("manifest", manifest)
        return StremioAddon.fromDescriptor(descriptor)
            ?: error("Failed to build test addon descriptor")
    }

    @Test
    fun manifestUrlIsNotKeptAsResourcePath() {
        assertEquals(
            "https://v3-cinemeta.strem.io/",
            StremioApi.resourceBase("https://v3-cinemeta.strem.io/manifest.json")
        )
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top/search=dead%20pool.json",
            StremioApi.resourceUrl(
                addon("https://v3-cinemeta.strem.io/manifest.json"),
                "catalog",
                "movie",
                "top",
                mapOf("search" to "dead pool")
            )
        )
    }

    @Test
    fun configQueryIsAppendedAfterResourcePath() {
        val addon = addon("https://addon.example/manifest.json?token=abc")
        assertEquals("https://addon.example/", StremioApi.resourceBase(addon.transportUrl))
        assertEquals(
            "https://addon.example/stream/movie/tt1431045.json?token=abc",
            StremioApi.resourceUrl(addon, "stream", "movie", "tt1431045", emptyMap())
        )
    }

    @Test
    fun percentEncodingMatchesStremioCore() {
        assertEquals("a-b_c.d!~*'()", StremioApi.encodeUriComponent("a-b_c.d!~*'()"))
        assertEquals("dead%20pool", StremioApi.encodeUriComponent("dead pool"))
        assertEquals("tt1431045%3A1%3A2", StremioApi.encodeUriComponent("tt1431045:1:2"))
    }

    @Test
    fun legacyTransportIsDetected() {
        assertTrue(StremioApi.isLegacyTransportUrl("https://opensubtitles.strem.io/stremio/v1"))
        assertTrue(!StremioApi.isLegacyTransportUrl("https://v3-cinemeta.strem.io/manifest.json"))
    }
}
