package dev.past.jetbrains

/**
 * Conservative redaction: the obvious shapes (keys, tokens, and assignments whose name says
 * "secret") never leave the machine by accident. It is no guarantee; a secret of another shape goes
 * through. The patterns are the ones every past.dev plugin applies.
 */
object Redaction {
    private val PATTERNS = listOf(
        Regex("""\b(?:past_sk|past_mk|sk-ant|sk-|ghp_|gho_|ghu_|ghs_|github_pat|xox[baprs]|AKIA|ASIA|glpat)-?[A-Za-z0-9_\-]{12,}"""),
        Regex("""\bBearer\s+[A-Za-z0-9._\-]{20,}""", RegexOption.IGNORE_CASE),
        Regex("""\beyJ[A-Za-z0-9._\-]{20,}"""),
        Regex("""\b([A-Z0-9_]*(?:SECRET|PASSWORD|TOKEN|API_?KEY|PRIVATE_?KEY|CREDENTIAL)[A-Z0-9_]*)\s*[=:]\s*\S+"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
    )

    fun redact(text: String): String = PATTERNS.fold(text) { out, pattern ->
        // Only the assignment pattern names what it hides; the name stays so the reader knows a
        // value was there.
        pattern.replace(out) { match ->
            val name = if (match.groups.size > 1) match.groups[1]?.value else null
            if (name != null) "$name=[redacted]" else "[redacted]"
        }
    }
}
