package li.mofanx.epso.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import li.mofanx.epso.app
import java.net.InetAddress

object NetworkUtils {
    fun isAvailable(): Boolean = try {
        InetAddress.getByName("www.baidu.com") != null
    } catch (_: Throwable) {
        false
    }

    fun isWifiConnected(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}