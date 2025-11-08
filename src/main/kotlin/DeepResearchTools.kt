package com.zxuhan

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

/**
 *  Deep Research Tools
 *
 * Usage Pattern
 * 1. Search for topic → get URLs
 * 2. Fetch top 2-3 URLs → get content
 * 3. Analyze progress → check if sufficient
 * 4. If gaps exist → targeted search
 * 5. Synthesize final answer
 *
 * @see SearchResults for search output format
 * @see WebContent for fetched content format
 * @see ResearchAnalysis for progress tracking
 */
class DeepResearchTools: ToolSet {

    //region HTTP client configuration
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }
    //endregion


    //region Tool 1: Search Web
    /**
     * Search using DuckDuckGo and returns up to 5 relevant results.
     *
     * @param query Search query string
     * @return SearchResults with titles, URLs, and snippets
     *
     * @see SearchResults for output format
     */
    @Tool
    @LLMDescription("""
        Search DuckDuckGo for information. Returns up to 5 relevant results with titles and URLs.
        Use this to find sources. Keep queries focused and specific.
    """)
    suspend fun searchWeb(
        @LLMDescription("Search query - be specific")
        query: String,
    ): SearchResults {
        // DuckDuckGo HTML search
        val searchUrl = URLBuilder("https://html.duckduckgo.com/html/")
            .apply {
                parameters.append("q", query)
            }
            .buildString()

        val html = httpClient.get(searchUrl).bodyAsText()
        val doc = Jsoup.parse(html)

        // Parse results from DuckDuckGo HTML
        val results = doc.select("div.result").take(5).mapIndexed { index, element ->
            val titleElement = element.selectFirst("a.result__a")
            val snippetElement = element.selectFirst("a.result__snippet")

            SearchResult(
                title = titleElement?.text() ?: "No title",
                url = titleElement?.attr("href")?.let {
                    // Clean up DuckDuckGo redirect URLs
                    it.substringAfter("uddg=").substringBefore("&")
                } ?: "",
                snippet = snippetElement?.text() ?: "No description",
                rank = index + 1
            )
        }.filter { it.url.isNotEmpty() }

        return SearchResults(results)
    }
    //endregion


    //region Tool 2: Fetch Web Content
    /**
     * Fetches and extracts clean text content from a URL.
     *
     * @param url Complete URL to fetch (must be valid HTTP/HTTPS)
     * @return WebContent with cleaned text
     *
     * @see WebContent for output format
     */
    @Tool
    @LLMDescription("""
        Fetch the main text content from a URL. Returns clean text without ads or navigation.
        Use this ONLY for the most relevant URLs from search results (top 2-3 max).
        The content will be truncated to 3000 characters to keep costs low.
    """)
    suspend fun fetchWebContent(
        @LLMDescription("Complete URL to fetch")
        url: String,
    ): WebContent {
        val html = httpClient.get(url).bodyAsText()
        val doc = Jsoup.parse(html)

        // Remove unwanted elements
        doc.select("script, style, nav, header, footer, iframe, .ad, .advertisement").remove()

        // Get main text content
        val text = doc.body().text()

        // Truncate to keep costs low (3000 chars ≈ 750 tokens)
        val truncated = if (text.length > 3000) {
            text.take(3000) + "\n\n[Content truncated for cost efficiency]"
        } else {
            text
        }

        return WebContent(
            url = url,
            content = truncated,
            fullLength = text.length
        )
    }
    //endregion


    //region Tool 3: Re-reason with Ranking
    /**
     * Analyzes research progress and determines if enough information is gathered
     *
     */
    @Tool
    @LLMDescription("""
        CRITICAL RESEARCH ANALYSIS TOOL - Use this after gathering information.
        
        This tool helps you structure your findings and decide next steps:
        1. RANK the sources you've read by relevance (1=most relevant)
        2. IDENTIFY what key information is still missing
        3. DECIDE if you have enough to answer the question well
    """)
    fun analyzeResearchProgress(
        @LLMDescription("Ranked list of sources by relevance. Format: '1. [URL] - why it's useful'")
        rankedSources: List<String>,
        @LLMDescription("Key facts you've learned so far - be specific and cite sources")
        keyFindings: List<String>,
        @LLMDescription("Specific gaps - what CRITICAL info is missing? Be realistic about what's needed.")
        criticalGaps: List<String>,
        @LLMDescription("Your assessment: 'sufficient' if you can answer well, 'needs_more' if critical gaps exist")
        readinessLevel: String,
    ): ResearchAnalysis {
        val isReady = readinessLevel.lowercase() == "sufficient"

        return ResearchAnalysis(
            sourcesReviewed = rankedSources.size,
            rankedSources = rankedSources,
            keyFindings = keyFindings,
            criticalGaps = criticalGaps,
            readinessLevel = readinessLevel,
            recommendation = if (isReady) {
                "Research complete. CALL synthesizeFinalAnswer with ${keyFindings.size} key findings."
            } else {
                "Need to address ${criticalGaps.size} critical gaps: ${criticalGaps.take(2).joinToString(", ")}"
            },
            shouldContinue = !isReady && criticalGaps.isNotEmpty()
        )
    }
    //endregion

    //region Tool 4: Synthesize Final Answer
    /**
     * Produces the final comprehensive answer by synthesizing all research findings.
     *
     * @see SynthesisSignal for output format
     */
    @Tool
    @LLMDescription("""
        FINAL SYNTHESIS TOOL - Call this when research is complete.
        
        This signals that you're ready to produce your comprehensive answer.
        After calling this, you MUST provide your final answer with:
        - Overview section
        - Main Features/Key Points
        - Additional Details
        - Sources Consulted
        
        Do NOT call any other tools after this.
    """)
    fun synthesizeFinalAnswer(
        @LLMDescription("Brief summary of what your answer will cover")
        summary: String,
    ): SynthesisSignal {
        return SynthesisSignal(
            ready = true,
            message = "Research phase complete. Now produce your comprehensive answer based on: $summary",
            instruction = "Provide structured answer with Overview, Main Features, Additional Details, and Sources sections."
        )
    }
    //endregion
}

//region Data Models
@Serializable
data class SynthesisSignal(
    val ready: Boolean,
    val message: String,
    val instruction: String,
) {
    override fun toString(): String {
        return buildString {
            appendLine("Ready to synthesize")
            appendLine("Summary: $message")
            appendLine("Next: $instruction")
        }.trim()
    }
}

@Serializable
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val rank: Int,
)

@Serializable
data class SearchResults(
    val results: List<SearchResult>,
) {
    override fun toString(): String {
        return """
            Found ${results.size} results:
            
            ${results.joinToString("\n\n") { result ->
            "[${result.rank}] ${result.title}\n    URL: ${result.url}\n    ${result.snippet}"
        }}
        """.trimIndent()
    }
}

@Serializable
data class WebContent(
    val url: String,
    val content: String,
    val fullLength: Int,
) {
    override fun toString(): String {
        return """
            Content from: $url
            Length: ${content.length} characters (full page: $fullLength chars)
            
            $content
        """.trimIndent()
    }
}

@Serializable
data class ResearchAnalysis(
    val sourcesReviewed: Int,
    val rankedSources: List<String>,
    val keyFindings: List<String>,
    val criticalGaps: List<String>,
    val readinessLevel: String,
    val recommendation: String,
    val shouldContinue: Boolean,
) {
    override fun toString(): String {
        return buildString {
            appendLine("STATUS: $readinessLevel | Sources: $sourcesReviewed | Continue: ${if (shouldContinue) "YES" else "NO"}")
            appendLine()

            if (rankedSources.isNotEmpty()) {
                appendLine("Sources: ${rankedSources.joinToString("; ")}")
            }

            if (keyFindings.isNotEmpty()) {
                appendLine("Findings: ${keyFindings.joinToString("; ")}")
            }

            if (criticalGaps.isNotEmpty()) {
                appendLine("Gaps: ${criticalGaps.joinToString("; ")}")
            }

            appendLine("Next: $recommendation")
        }.trim()
    }
}
//endregion