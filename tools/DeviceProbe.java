import io.github.dcl.app.RootClient;
import org.json.JSONObject;

/** Separate on-device protocol probe; never packaged in the application. */
public final class DeviceProbe {
    public static void main(String[] args) throws Exception {
        if(args.length < 1 || args.length > 2) throw new IllegalArgumentException("action [json]");
        System.out.println(RootClient.call(args[0], args.length == 2 ? new JSONObject(args[1]) : new JSONObject()));
    }
}
