package io.github.dovecoteescapee.byedpi.warp

import android.content.Context
import io.github.dovecoteescapee.byedpi.utility.getPreferences

object WarpState {
    @Volatile var active=false
    @Volatile var status="WARP ещё не запускался"
    @Volatile var detail=""
    fun selected(context: Context)=context.getPreferences().getString("pilot_transport","local")=="warp"
    fun report()="DPI Pilot · WARP\n$status\n$detail\nКлючи скрыты. Рукопожатие и счётчики не гарантируют воспроизведение всех видео."
}
