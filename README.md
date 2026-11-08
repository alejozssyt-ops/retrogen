# Retrogen Mods

Mod de Fabric para **Minecraft 1.21.11** (Fabric Loader 0.19.3, Fabric API 0.141.6+1.21.11) que aplica
la generación de otros mods (por defecto **Ecologics** y **Moog's Soaring Structures**) en chunks
que ya existían antes de instalar esos mods, estilo "Retrogen". En chunks nuevos no interviene:
ahí los mods generan normalmente.

## Cómo funciona

1. Cada chunk guarda en disco una marca con los mods a los que ya se les aplicó retrogen.
2. Al cargar un chunk que viene de disco y no tiene la marca, entra en una cola.
3. Cada tick se procesan unos pocos chunks de la cola: primero estructuras y luego features
   (árboles, flores, etc.), solo las de los namespaces configurados.
4. El chunk se marca y no se vuelve a tocar.

## Configuración (`config/retrogen_mods.json`)

| Opción | Qué hace |
|---|---|
| `namespaces` | ids de los mods a aplicar (por defecto `ecologics`, `moogs_soaring_structures`) |
| `chunksPerTick` | chunks procesados por tick |
| `retrogenStructures` / `retrogenFeatures` | activar o desactivar cada parte |
| `version` | súbelo para repetir el retrogen en todos los chunks |

## Compilar

Con Gradle 9 instalado: `gradle build`. El jar queda en `build/libs/`.
También lo compila GitHub Actions en cada push (pestaña Actions, artefacto `retrogen-mods-jar`).

## Limitaciones

- Las estructuras grandes pueden cargar chunks vecinos al colocarse.
- Las estructuras retrogeneradas no se registran en los chunks vecinos (`/locate` puede no verlas).
- Muestrea el bioma en 5 puntos por chunk para decidir qué features aplicar.
