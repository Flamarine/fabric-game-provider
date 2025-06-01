# Better Than Adventure Game Provider

This Fabric Game Provider directly launches the game via stubbed classes.

## Stubs

Each of the game providers must provide an entrypoint method and a version string.

This is where we're currently getting them from.
If these aren't sufficient, a new implementations should be added.
Then add them to the list below.

### Client

### Version:

- `net.minecraft.client.Minecraft#VERSION String`

#### Entrypoint

- `net.minecraft.client.Minecraft.main(String[])`

### Server

### Version:

- `net.minecraft.server.MinecraftServer#VERSION String`

#### Entrypoint:

- `net.minecraft.server.MinecraftServer#main(String[])`
