package nc.ticagou.common;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Adresse du poste client : celle transmise par le proxy HTTPS (server.forward-headers-strategy). */
public final class ClientIp {

    private ClientIp() {
    }

    public static String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    public static boolean isLoopback(String address) {
        if (address == null) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
