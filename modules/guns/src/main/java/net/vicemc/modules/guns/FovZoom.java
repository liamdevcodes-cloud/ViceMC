package net.vicemc.modules.guns;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client-side field-of-view zoom while aiming. Paper 1.21 has no FOV API
 * (no {@code Player#setFov}, no {@code minecraft:fov} attribute), so the same
 * approach used by production gun plugins is applied here: a crafted
 * {@code ClientboundPlayerAbilitiesPacket} whose walking speed the client
 * uses to derive its field of view. Only the sent packet is affected, so the
 * player's real movement and abilities are untouched.
 *
 * <p>The walking speed that produces a given magnification follows:
 * <pre>
 *     walkingSpeed = 1 / (20 / magnification - 10)
 * </pre>
 * with {@code 1.0} magnification (no zoom) mapping to the vanilla {@code 0.1}.
 *
 * <p>Everything is accessed via reflection so the module still compiles
 * against paper-api only; if the internal Minecraft names ever change, the
 * zoom silently degrades to a no-op instead of breaking the module.
 */
public final class FovZoom {

    private static final Logger LOGGER = Logger.getLogger("ViceGuns");
    private static final String ABILITIES_CLS = "net.minecraft.world.entity.player.Abilities";
    private static final String PACKET_CLS = "net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket";
    private static final String PACKET_IFACE = "net.minecraft.network.protocol.Packet";

    private static final String[] ABILITY_FLAGS = {
            "invulnerable", "flying", "mayfly", "instabuild", "mayBuild", "flyingSpeed"
    };

    private static boolean resolved;
    private static boolean broken;

    private static Class<?> abilitiesType;
    private static Constructor<?> packetConstructor;
    private static Method getHandleMethod;
    private static Method getAbilitiesMethod;
    private static Field connectionField;
    private static Method sendMethod;
    private static Method setWalkingSpeedMethod;
    private static Field walkingSpeedField;

    private FovZoom() {
    }

    /** Zooms the player's view in by the given magnification (1.0 = no zoom). */
    public static void zoom(Player player, double magnification) {
        if (player == null || !player.isOnline() || magnification <= 1.0) {
            return;
        }
        send(player, (float) walkSpeedForZoom(magnification));
    }

    /** Restores the FOV the server currently expects (re-sends real abilities). */
    public static void reset(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        send(player, null);
    }

    /** Converts a magnification into the abilities walking speed the client needs. */
    static double walkSpeedForZoom(double magnification) {
        if (magnification <= 1.0) {
            return 0.1;
        }
        if (magnification >= 10.0) {
            return -0.125;
        }
        return 1.0 / (20.0 / magnification - 10.0);
    }

    private static void send(Player player, Float walkingSpeed) {
        try {
            resolve(player);
            if (broken) {
                return;
            }
            Object handle = getHandleMethod.invoke(player);
            Object abilities = getAbilitiesMethod.invoke(handle);
            Object fake = copyAbilities(abilities, walkingSpeed);
            Object packet = packetConstructor.newInstance(fake);
            Object connection = connectionField.get(handle);
            sendMethod.invoke(connection, packet);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            broken = true;
            LOGGER.log(Level.WARNING, "FOV zoom is unavailable, disabling: " + ex.getMessage(), ex);
        }
    }

    private static Object copyAbilities(Object source, Float walkingSpeed) throws ReflectiveOperationException {
        Object fake = abilitiesType.getDeclaredConstructor().newInstance();
        for (String fieldName : ABILITY_FLAGS) {
            Field field = abilitiesType.getField(fieldName);
            field.set(fake, field.get(source));
        }
        if (walkingSpeed != null) {
            setWalkingSpeedMethod.invoke(fake, walkingSpeed);
        } else {
            walkingSpeedField.set(fake, walkingSpeedField.get(source));
        }
        return fake;
    }

    private static void resolve(Player player) throws ReflectiveOperationException {
        if (resolved || broken) {
            return;
        }
        Class<?> packetType = Class.forName(PACKET_IFACE);
        abilitiesType = Class.forName(ABILITIES_CLS);
        packetConstructor = Class.forName(PACKET_CLS).getConstructor(abilitiesType);
        getHandleMethod = player.getClass().getMethod("getHandle");
        Object handle = getHandleMethod.invoke(player);
        Class<?> handleType = handle.getClass();
        getAbilitiesMethod = handleType.getMethod("getAbilities");
        connectionField = handleType.getField("connection");
        sendMethod = connectionField.getType().getMethod("send", packetType);
        setWalkingSpeedMethod = abilitiesType.getMethod("setWalkingSpeed", float.class);
        walkingSpeedField = abilitiesType.getField("walkingSpeed");
        resolved = true;
    }
}
