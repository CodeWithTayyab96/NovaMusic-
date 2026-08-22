package com.novamusic.app.innertube.models

import com.novamusic.app.innertube.models.response.GetSearchSuggestionsResponse
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GetSearchSuggestionsResponseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun deserializesSearchSuggestions() {
        val input = """
            {
              "contents": [
                {
                  "searchSuggestionsSectionRenderer": {
                    "contents": [
                      {
                        "searchSuggestionRenderer": {
                          "suggestion": { "runs": [ { "text": "never gonna give you up", "navigationEndpoint": null } ] },
                          "navigationEndpoint": {
                            "searchEndpoint": { "query": "never gonna give you up", "params": null }
                          }
                        },
                        "musicResponsiveListItemRenderer": null
                      },
                      {
                        "searchSuggestionRenderer": {
                          "suggestion": { "runs": [ { "text": "rick astley", "navigationEndpoint": null } ] },
                          "navigationEndpoint": {
                            "searchEndpoint": { "query": "rick astley", "params": null }
                          }
                        },
                        "musicResponsiveListItemRenderer": null
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<GetSearchSuggestionsResponse>(input)

        val renderer = response.contents?.first()?.searchSuggestionsSectionRenderer
        assertEquals(2, renderer?.contents?.size)

        val first = renderer?.contents?.first()?.searchSuggestionRenderer
        assertEquals("never gonna give you up", first?.suggestion?.runs?.first()?.text)
        assertEquals(
            "never gonna give you up",
            first?.navigationEndpoint?.searchEndpoint?.query,
        )
        assertNull(first?.navigationEndpoint?.searchEndpoint?.params)

        val second = renderer?.contents?.get(1)?.searchSuggestionRenderer
        assertEquals("rick astley", second?.suggestion?.runs?.first()?.text)
        assertEquals("rick astley", second?.navigationEndpoint?.searchEndpoint?.query)
    }

    @Test
    fun handlesEmptyContents() {
        val input = """{ "contents": [] }"""

        val response = json.decodeFromString<GetSearchSuggestionsResponse>(input)

        assertEquals(0, response.contents?.size)
    }

    @Test
    fun handlesExplicitNullContents() {
        val input = """{ "contents": null }"""

        val response = json.decodeFromString<GetSearchSuggestionsResponse>(input)

        assertNull(response.contents)
    }

    @Test(expected = MissingFieldException::class)
    fun missingContentsThrows() {
        // `contents` has no default value, so an absent field is a required-field
        // violation (this documents the model contract for real API responses).
        val input = """{ "unknownField": true }"""

        json.decodeFromString<GetSearchSuggestionsResponse>(input)
    }
}
