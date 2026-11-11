package app.lume.reader.data

/**
 * Mede a velocidade de leitura (palavras por minuto) a partir de amostras periódicas
 * da posição no livro. Pulos grandes (ex.: arrastar a barra de progresso) e tempo parado
 * são descartados para não distorcer a média.
 */
class ReadingSpeed(
    var totalWords: Double = 0.0,
    var totalMs: Double = 0.0,
) {
    private var lastPos = -1.0
    private var lastTime = 0L
    private var idleMs = 0L
    private var pendingIdleMs = 0L

    /** Palavras por minuto; usa o padrão até ter dados suficientes. */
    val wpm: Double
        get() = if (isCalibrated) (totalWords / (totalMs / 60000.0)).coerceIn(MIN_WPM, MAX_WPM) else DEFAULT_WPM

    val isCalibrated: Boolean
        get() = totalMs >= MIN_CALIBRATION_MS && totalWords >= MIN_CALIBRATION_WORDS

    /** Esquece a última posição (chamar ao abrir o livro ou depois de um salto manual). */
    fun resetBaseline() {
        lastPos = -1.0
        idleMs = 0
        pendingIdleMs = 0
    }

    fun sample(wordPos: Double, nowMs: Long) {
        if (lastPos < 0) {
            lastPos = wordPos
            lastTime = nowMs
            return
        }
        val dt = nowMs - lastTime
        val dw = wordPos - lastPos
        lastPos = wordPos
        lastTime = nowMs
        if (dt <= 0 || dt > MAX_SAMPLE_GAP_MS) {
            pendingIdleMs = 0
            idleMs = 0
            return
        }
        when {
            dw < 0 || dw > MAX_WORDS_PER_MIN_JUMP * dt / 60000.0 + JUMP_SLACK_WORDS -> {
                // voltou no texto ou pulou longe: não conta
                pendingIdleMs = 0
                idleMs = 0
            }
            dw == 0.0 -> {
                // parado: o tempo só conta se depois houver avanço (leu a tela e rolou)
                idleMs += dt
                if (idleMs <= MAX_IDLE_MS) pendingIdleMs += dt else pendingIdleMs = 0
            }
            else -> {
                totalWords += dw
                totalMs += dt + pendingIdleMs
                pendingIdleMs = 0
                idleMs = 0
                decay()
            }
        }
    }

    /** Mantém a média "recente": limita o histórico para a velocidade se adaptar com o tempo. */
    private fun decay() {
        if (totalMs > HISTORY_MS) {
            val f = HISTORY_MS / totalMs
            totalWords *= f
            totalMs *= f
        }
    }

    companion object {
        const val DEFAULT_WPM = 200.0
        const val MIN_WPM = 40.0
        const val MAX_WPM = 900.0
        const val MIN_CALIBRATION_MS = 120_000.0
        const val MIN_CALIBRATION_WORDS = 150.0
        const val MAX_SAMPLE_GAP_MS = 60_000L
        const val MAX_IDLE_MS = 300_000L
        const val MAX_WORDS_PER_MIN_JUMP = 1500.0
        const val JUMP_SLACK_WORDS = 900.0
        const val HISTORY_MS = 3 * 3600_000.0
    }
}
