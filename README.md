# SignGUI — Gamai fork

Fork of [Rapha149/SignGUI](https://github.com/Rapha149/SignGUI), version `2.5.5-gamai`.
Adds Mojang-mapped Minecraft 26.2 and 26.3 support without changing the public API.

Build the fork with **Gradle and JDK 25**, using an unpacked local Leaf 26.3 installation:

```sh
./gradlew clean build publishToMavenLocal -PminecraftData=/path/to/minecraft-data
```

The default data path is `../Servers/docker-server/minecraft-data`. It must contain
`versions/26.3/leaf-26.3.jar`,
`libraries/cn/dreeam/leaf/leaf-api/26.3.local-SNAPSHOT/leaf-api-26.3.local-SNAPSHOT.jar`,
and the server's other libraries. These are compile-only file dependencies.
Output: `build/libs/signgui-2.5.5-gamai.jar`, also installed as
`de.rapha149.signgui:signgui:2.5.5-gamai` in Maven Local. This version is not on Maven Central.

The API/shared wrapper classes are rebuilt from source with Java 8 bytecode.
Unchanged older NMS wrappers are copied byte-for-byte from the upstream `2.5.4`
release, so obsolete server artifacts are not needed to build the fork.
The original Maven module builds remain available for upstream development;
use Gradle to build this fork including the new `Mojang26_R1` sources.

Minecraft 26.2 selects `MojangWrapper26_2`, which inherits the compatible boolean
sign-side implementation. Minecraft 26.3 selects `MojangWrapper26_R1`, using
`SignTextSlot`, mutable sign text, and record packet accessors. Later unknown
26.x versions select the latter as a best-effort fallback and need verification
against their server jars. Both regular Bukkit versions and Paper
`26.3.build.N-alpha` version strings are recognized.

For a consumer without Maven Local, vendor the jar and keep the usual relocation:

```kotlin
implementation(files("libs/signgui-2.5.5-gamai.jar"))
```

## Upstream documentation

An api to get input text via a sign in Minecraft.  
The api supports the Minecraft versions from `1.8` to `1.21`.  
Also supports adventure text and mojang-mapped Paper plugins (1.20.5+).

## Integration

Maven dependency (add Maven Local to the consumer's repositories):
```xml
<dependency>
    <groupId>de.rapha149.signgui</groupId>
    <artifactId>signgui</artifactId>
    <version>2.5.5-gamai</version>
</dependency>
```

In order to avoid conflicts with other plugins that also use this api, relocate the package in your maven shade plugin:
```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-shade-plugin</artifactId>
            <version>SHADE_VERSION</version> <!-- The version must be at least 3.5.0 -->
            <executions>
                <execution>
                    <phase>package</phase>
                    <goals>
                        <goal>shade</goal>
                    </goals>
                    <configuration>
                        <relocations>
                            <relocation>
                                <pattern>de.rapha149.signgui</pattern>
                                <!-- Replace [YOUR_PLUGIN_PACKAGE] with your namespace -->
                                <shadedPattern>[YOUR_PLUGIN_PACKAGE].signgui</shadedPattern>
                            </relocation>
                        </relocations>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

<details>
<summary><h3>Issues with Gradle's minimize() method</h3></summary>

My API loads it's version wrapper classes via Reflection because otherwise the imports not corresponding to the current Minecraft version would cause an error.
Unfortunately, this clashes with Gradle's `minimize()` method because that method causes the compiler to ignore any classes that weren't explicitly used in the code.  
There are two solution to this problem:
1. Explicitly use the correct wrapper class in your code.
   This only works for plugins which are intended to only work on one specific Minecraft version as using a wrapper class that doesn't correspond to the Minecraft version causes errors due to the reasons above.
   Anyway, this is how you could do it:
   ```java
   Wrapper1_20_R4.class.getName()
   ```
   I used the class `Wrapper1_20_R4` in this example which corresponds to the Minecraft version `1.20.5` and `1.20.6`.
   If you are using a mojang-mapped Paper plugin the class would be `MojangWrapper1_20_R4`.  
   In order to find out which Minecraft version corresponds to which wrapper class you can check out this Github repository of mine: [NMSVersions](https://github.com/Rapha149/NMSVersions?tab=readme-ov-file#versions).  
   ​
3. Exclude the SignGUI dependency from being affected by the `minimize()` method like this:
   ```gradle
   minimize() {
       // exclude every version of the SignGUI dependency using a Regex string
       exclude(dependency("de\\.rapha149\\.signgui:signgui:.*"))
   }
   ```
   This solution will cause all wrapper classes to compile. Even if your plugin only supports Minecraft 1.17+ it will also compile the wrapper classes for versions up to 1.16.5.
   However, it's not that much code and there is, to my knowledge, no better solution.
</details>

## Usage
To open a sign editor gui for a player, do the following:
```java
try {
    SignGUI gui = SignGUI.builder()
        // set lines
        .setLines("§6Line 1", null, "§6Line 3")
    
        // set specific line, starting index is 0
        .setLine(3, "Line 4")
    
        // set the sign type
        .setType(Material.DARK_OAK_SIGN)
    
        // set the sign color
        .setColor(DyeColor.YELLOW)
    
        // set the handler/listener (called when the player finishes editing)
        .setHandler((p, result) -> {
            // get a speficic line, starting index is 0
            String line0 = result.getLine(0);
    
            // get a specific line without color codes
            String line1 = result.getLineWithoutColor(1);
    
            // get all lines
            String[] lines = result.getLines();
    
            // get all lines without color codes
            String[] linesWithoutColor = result.getLinesWithoutColor();
    
            if (line1.isEmpty()) {
                // The user has not entered anything on line 2, so we open the sign again
                return List.of(SignGUIAction.displayNewLines("§6Line 1", null, "§6Line 3", "Line 4"));
            }
    
            if (line1.equals("inv")) {
                // close the sign and open an inventory
                return List.of(
                    // "this" = your JavaPlugin instance
                    SignGUIAction.openInventory(this, Bukkit.createInventory(player, 27)),
                    SignGUIAction.run(() -> player.sendMessage("Inventory opened!"))
                );
            }
    
            // Just close the sign by not returning any actions
            return Collections.emptyList();
        })
    
        // build the SignGUI
        .build();
    
    // open the sign
    gui.open(player);
    
    // you can also open the sign for multiple players
    gui.open(player2);
} catch (SignGUIVersionException e) {
    // This error is thrown if SignGUI does not support this server version (yet).
}
```

You don't have to call all methods. Only `setHandler` is mandatory.

By default, the handler is called by an asynchronous thread. You can change that behaviour by calling the method `callHandlerSynchronously` of the builder.
An explanation for the different methods can be found on the [Javadoc](https://javadoc.io/doc/de.rapha149.signgui/signgui).

Also: as you can see the code catches the `SignGUIVersionException` which could be thrown by `SignGUI.builder()` if the api does not support the Minecraft version of the server.
If you're interested in why this exception exists, check these [release notes](https://github.com/Rapha149/SignGUI/releases/tag/v2.5.0).  

## Limitations

### Players can edit pre-written lines
Due to the nature of sign editing, it is not possible to specify the lines a player can and can't edit. If you set pre-written lines on the sign (e.g. for informing the player what he should write), the player will be able to delete or edit these lines as well. My recommendation is to ignore the pre-written lines when analyzing the player's response so that it doesn't matter if the player alters them and to leave the first line(s) blank for the player to write on so that he doesn't have to select a lower line first.

### The location of the sign
Especially in newer versions, I encountered the problem that the sign had to be near the player in order to edit it.  
In older versions it worked by placing the sign at the bottom of the world but that does not seem to be the case anymore, in my tests anyway.

Because of that the default location is three blocks behind the player (three blocks in the opposite view direction).
The only problem with this is that if you tell the api to redisplay the sign if the player e.g. typed something wrong, the player could slowly turn and then see the sign because there is a slight delay before the gui opens again and in that time the player can turn around a little bit.  
You are, naturally, free to experiment with the location of the sign yourself.

Of course the sign is not really placed, it's just sent to the player, so other players won't see it.

### Opening a sign after a player joins
Since the sign is not actually placed on the server, it can get overwritten when the chunks are sent to the player, which is the case when the player joins.

Because of that you may encounter the problem that the sign does not display any text when you send it to the player directly when he joins.  
But even with a 20 tick (1 second) delay after the PlayerJoinEvent, the sign in my tests was empty sometimes.  
I would recommend waiting at least a few more seconds before opening the gui.

### Not being able to change the glow status when displaying new lines
At least to my knowledge it is not possible to change whether the sign's text glows when displaying new lines using the respective SignGUIAction.
To do this you would need to construct and open a new SignGUI.

## Credits
This project's structure was inspired by WesJD's [AnvilGUI](https://github.com/WesJD/AnvilGUI) and I used some code from Cleymax's [SignGUI](https://github.com/Cleymax/SignGUI).
