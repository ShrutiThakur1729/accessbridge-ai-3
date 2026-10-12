package com.accessbridgeai.detect

import com.accessbridgeai.screen.Fixtures
import com.accessbridgeai.utils.PaymentGate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PaymentGateFixturesTest {
    private fun text(name: String) = ScreenView(Fixtures.load(name).screen).allText()

    @Test fun reportWhichFixturesTheGateWouldStop() {
        val sb = StringBuilder()
        for (name in Fixtures.allNames()) sb.appendLine("$name -> ${PaymentGate.matched(text(name)) ?: "-"}")
        File("build/payment-gate-report.txt").apply { parentFile?.mkdirs() }.writeText(sb.toString())
    }

    @Test fun theCartScreenIsAPaymentStop() {
        assertTrue(PaymentGate.isPayment(text("zepto_04_cart")))
    }
}