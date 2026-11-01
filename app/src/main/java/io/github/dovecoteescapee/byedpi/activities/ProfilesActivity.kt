package io.github.dovecoteescapee.byedpi.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Keep old shortcuts valid; the fixed-purpose product has one profile/diagnostics screen. */
class ProfilesActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        startActivity(Intent(this,ServiceTuningActivity::class.java));finish()
    }
}
