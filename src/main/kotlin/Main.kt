package com.zxuhan

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor

suspend fun main() {

    val openAIApiKey = "YOUR_OPENAI_API_KEY"

    val promptExecutor = simpleOpenAIExecutor(openAIApiKey)

    val deepResearchTools = DeepResearchTools()

    // Register tools with the agent
    val toolRegistry = ToolRegistry {
        tools(deepResearchTools)
    }

    // Agent Configuration with system prompt
    val agentConfig = AIAgentConfig(
        prompt = Prompt.build("deep_research_prompt") {
            system(
                """
                You are a deep research assistant that conducts thorough investigations on topics.
                
                COST-EFFICIENT RESEARCH PROCESS:
                1. SEARCH: Start with 1-2 broad searches to understand the topic
                2. FETCH: Read ONLY the top 2-3 most relevant sources (not all results!)
                3. ANALYZE: Use the analyzeResearchProgress tool to:
                   - Rank sources by relevance
                   - Identify what critical information is still missing
                   - Decide if you have enough to provide a good answer
                4. TARGETED SEARCH: If gaps exist, do 1-2 more SPECIFIC searches
                5. SYNTHESIZE: When analyzeResearchProgress shows "sufficient":
                   - Call synthesizeFinalAnswer tool with a brief summary
                   - Then provide your comprehensive structured answer
                
                EFFICIENCY RULES:
                - Limit yourself to 6-8 total tool calls maximum
                - Don't fetch from every search result - pick the best 2-3
                - Use analyzeResearchProgress after every 2-3 tool calls
                - Stop when you have sufficient information, not perfect information
                - Focus on answering the core question, not exhaustive research
                
                When analyzeResearchProgress returns "sufficient":
                1. MUST call synthesizeFinalAnswer(summary="...")
                2. THEN provide your final answer in this format:
                
                Your final response MUST be a comprehensive answer structured as:
                
                ## Overview
                [Brief introduction to the topic]
                
                ## Main Features/Key Points
                [Detailed bullet points with specific information you found]
                
                ## Additional Details
                [Any other relevant information]
                
                ## Sources Consulted
                [List the URLs you visited]
                
                DO NOT end with generic statements like "feel free to ask". 
                ALWAYS provide a complete, informative answer based on what you researched.
                """.trimIndent()
            )
        },
        model = OpenAIModels.Chat.GPT4o,
        maxAgentIterations = 50,
    )

    /**
     * Agent Initialization uses ReAct strategy
     */
    val agent = AIAgent<String, String>(
        promptExecutor = promptExecutor,
        strategy = reActStrategy(
            reasoningInterval = 1, // Reason after every action for better decision-making
            name = "deep_research_agent"
        ),
        toolRegistry = toolRegistry,
        agentConfig = agentConfig,
    ) {
        handleEvents {
            // print tools and args to be called
            onToolCallStarting { ctx ->
                println("\n>>> TOOL CALL: ${ctx.tool.name}")
                println("    Arguments: ${ctx.toolArgs}")
            }

            // Preview of results (first 150 chars)
            onToolCallCompleted { ctx ->
                println("<<< TOOL COMPLETED: ${ctx.tool.name}")
                val resultPreview = ctx.result.toString().take(150)
                println("    Result preview: $resultPreview...")
            }
        }
    }

    // Research query - modify this to research any topic
    val query = "What is the Koog AI framework and what are its main features?"

    println("=".repeat(80))
    println("DEEP RESEARCH QUERY:")
    println(query)
    println("=".repeat(80))
    println("\nStarting research... (Demo mode: cost-efficient)\n")

    // Run agent to research
    val result: String = agent.run(query)


    println("\n" + "=".repeat(80))
    println("RESEARCH RESULTS:")
    println("=".repeat(80))
    println(result)

}
