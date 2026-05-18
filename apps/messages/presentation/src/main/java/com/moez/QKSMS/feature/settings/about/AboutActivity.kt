package dev.octoshrimpy.quik.feature.settings.about

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.common.util.extensions.applySystemBarPadding
import dev.octoshrimpy.quik.databinding.ContainerActivityBinding

class AboutActivity : QkThemedActivity() {
    private lateinit var binding: ContainerActivityBinding
    private lateinit var router: Router

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ContainerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()

        router = Conductor.attachRouter(this, binding.container, savedInstanceState)
        if (!router.hasRootController()) {
            router.setRoot(RouterTransaction.with(AboutController()))
        }
    }

    override fun onBackPressed() {
        if (!router.handleBack()) {
            super.onBackPressed()
        }
    }
}
