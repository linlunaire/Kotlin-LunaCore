# Kotlin LunaCore notices

Kotlin LunaCore 0.3.0 and later are distributed under the GNU Lesser General Public License, version 3 or (at your option) any later version (`LGPL-3.0-or-later`). Copyright (c) 2026 linlunaire. See [LICENSE](LICENSE) for the additional permissions and [COPYING](COPYING) for the incorporated GPLv3 terms. The software is provided without warranty.

Versions through 0.2.1 were distributed under MIT. Their existing permissions remain available. The complete former notice, including Jonathan Ho and Zbx1425's copyright notices for the MTR/ANTE policy ancestry, is preserved in [licenses/LunaCore-MIT.txt](licenses/LunaCore-MIT.txt). The new distribution does not remove those notices or claim third-party work as original work. The three policy files remain under their existing JVM packages; 0.3.0 makes no behavior or ABI change to them.

Kotlin stdlib is an unmodified nested dependency under Apache-2.0. Its license and JetBrains attribution are in [licenses/Kotlin-LICENSE.txt](licenses/Kotlin-LICENSE.txt) and [licenses/Kotlin-NOTICE.txt](licenses/Kotlin-NOTICE.txt). It is not relicensed to LGPL. Gradle wrapper and other third-party build tools retain their own licenses.

The historical `transit-core-0.1.0` archive keeps its original licenses. Reforges-derived gameplay belongs to the separate GPL Luna Reforged project and is not part of this library.

Redistributions should keep LunaCore replaceable as a separate loader mod, include the license and notice files, and provide the corresponding LunaCore source and build scripts for the version shipped. Ordinary JVM linkage does not make a consumer part of this library; applicable LGPL requirements still govern redistribution and library modifications.

README organization takes inspiration from [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin). Its implementation, text and branding were not copied. Kotlin LunaCore provides ordinary JVM entrypoints and the stdlib; it does not implement FLK/KFF language adapters or their additional libraries.
