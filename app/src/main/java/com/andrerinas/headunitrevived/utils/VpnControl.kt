package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.content.Intent
import com.andrerinas.headunitrevived.aap.DummyVpnService

/**
 * Универсальный контроллер VPN.
 * В сборке GitHub запускает DummyVpnService.
 * В сборке Play Store методы не выполняют действий (заглушка).
 */
object VpnControl {
    fun startVpn(context: Context) {
        AppLog.i("VpnControl: Starting VPN service")
        try {
            // Проверяем, существует ли класс DummyVpnService (есть только в GitHub сборке)
            // Если класса нет (Play Store), ничего не делаем
            val intent = Intent(context, DummyVpnService::class.java)
            context.startService(intent)
        } catch (e: Exception) {
            // Игнорируем ошибку, если сервис недоступен (например, в Play Store сборке)
            AppLog.e("VpnControl: Failed to start VPN (service may not be available)", e)
        }
    }

    fun stopVpn(context: Context) {
        AppLog.i("VpnControl: Stopping VPN service")
        try {
            val intent = Intent(context, DummyVpnService::class.java).apply { 
                action = "com.andrerinas.headunitrevived.aap.ACTION_STOP_VPN"
            }
            context.startService(intent)
        } catch (e: Exception) {
            AppLog.e("VpnControl: Failed to stop VPN (service may not be available)", e)
        }
    }
    
    fun isVpnAvailable(): Boolean {
        // Возвращаем true, если сервис доступен
        return try {
            Class.forName("com.andrerinas.headunitrevived.aap.DummyVpnService")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
