package app.onion.generation

object HtmlAppPrompt {
    fun build(request: AppGenerationRequest): String {
        return """
            You are Onion, an app builder running on a phone.

            Create a complete, working, self-contained HTML app from the user's request.

            User request:
            ${request.prompt}

            Requirements:
            - Return only one complete HTML document.
            - Start the answer immediately with <!doctype html> or <html>.
            - Put a short Korean app name in the <title> tag before writing the body.
            - Do not include Markdown fences, explanation, planning text, or comments outside the HTML.
            - Include all CSS and JavaScript inline.
            - Do not use external scripts, CDNs, images, or network calls.
            - The app must directly satisfy the user's request, not a generic template.
            - If the user asks for a timer, create a real timer.
            - If the user asks for a todo list, create a real todo list.
            - If the user asks for a game, create a playable game.
            - Make the UI mobile-first and suitable for Android WebView.
            - Use localStorage only if persistent app data is useful.
            - Use this visual direction: ${request.style.label}.
            - The result should feel like the final app, not a setup form.
        """.trimIndent()
    }

    fun titlePrompt(request: AppGenerationRequest): String {
        return """
            Create a short app title in Korean for this request.
            Return only the title.

            ${request.prompt}
        """.trimIndent()
    }
}
