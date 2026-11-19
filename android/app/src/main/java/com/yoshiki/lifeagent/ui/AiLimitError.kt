package com.yoshiki.lifeagent.ui

import retrofit2.HttpException

internal fun Throwable.aiLimitMessage(): String? = when ((this as? HttpException)?.code()) {
    429 -> "本日のAI利用上限に達しました。日本時間の翌日0時以降に利用できます"
    413 -> "AIに渡す情報が上限を超えました。入力を短くして再試行してください"
    else -> null
}
