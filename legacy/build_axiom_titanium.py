#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
===============================================================================
  AXIOM 3.0 × ASTRAEA 5.0 — OBSIDIAN & TITANIUM EDITION (APK AUTO-BUILDER)
  Verze: 5.5-TITANIUM (polished)
===============================================================================
  Vygeneruje kompletní Android projekt AxiomOffline (cz.axiom.offline):
  • STEP-0 math parser, +EV engine, Half-Kelly, RANDY protokol, forensic scan
  • Převody jednotek, stavební kalkulace, import z Japonska
  • Černo-titanový vzhled + vektorové logo (volitelně PNG z 4482.png)
  • Připraví .github/workflows/build-apk.yml pro cloudový build
===============================================================================
"""

import os
import shutil
import zipfile
from pathlib import Path

PROJECT_DIR = Path("AxiomOffline")
ZIP_NAME = Path("AxiomOffline_Titanium_APK_Ready.zip")
USER_LOGO = Path("4482.png")
WORKFLOW_FILE = Path(".github/workflows/build-apk.yml")

FILES = {}

# =============================================================================
# 1. GRADLE KONFIGURACE
# =============================================================================
FILES["settings.gradle.kts"] = """pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AxiomOffline"
include(":app")
"""

FILES["build.gradle.kts"] = """plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
"""

FILES["gradle.properties"] = """org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
"""

FILES[".gitignore"] = """.gradle/
build/
local.properties
.idea/
*.iml
captures/
.externalNativeBuild/
.cxx/
"""

# =============================================================================
# 2. GITHUB ACTIONS WORKFLOW
# =============================================================================
FILES[".github/workflows/build-apk.yml"] = """name: Build AXIOM Titanium APK

on:
  push:
    branches: [ "**" ]
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build-titanium-apk:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Nastavení Java JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Nastavení Gradle 8.5
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.5'

      - name: Generování projektu z build_axiom_titanium.py
        run: |
          if [ ! -f "settings.gradle.kts" ] && [ -f "build_axiom_titanium.py" ]; then
            echo "→ Generuji Android projekt..."
            python3 build_axiom_titanium.py
            cp -r AxiomOffline/. .
            rm -rf AxiomOffline
            echo "→ Projekt rozbalen do rootu"
          fi
          if [ -f "4482.png" ] && [ ! -f "app/src/main/res/drawable/logo_titanium.png" ]; then
            mkdir -p app/src/main/res/drawable
            cp 4482.png app/src/main/res/drawable/logo_titanium.png
            echo "→ Vložena HD ikona"
          fi

      - name: Unit testy (STEP-0, +EV, RANDY, UNITS, FORENSIC)
        run: gradle testDebugUnitTest --no-daemon

      - name: Sestavení APK
        run: gradle assembleDebug --no-daemon

      - name: Vystavení APK ke stažení
        uses: actions/upload-artifact@v4
        with:
          name: AXIOM-TITANIUM-INSTALLER-APK
          path: app/build/outputs/apk/debug/app-debug.apk
          retention-days: 30
"""

# =============================================================================
# 3. APP BUILD.GRADLE.KTS
# =============================================================================
FILES["app/build.gradle.kts"] = """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.axiom.offline"
    compileSdk = 34

    defaultConfig {
        applicationId = "cz.axiom.offline"
        minSdk = 26
        targetSdk = 34
        versionCode = 55
        versionName = "5.5-TITANIUM"
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isDebuggable = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
"""

FILES["app/src/main/AndroidManifest.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AxiomTitanium">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""

# =============================================================================
# 4. ZDROJE: BARVY, TÉMA, IKONY
# =============================================================================
FILES["app/src/main/res/values/strings.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">AXIOM</string>
</resources>
"""

FILES["app/src/main/res/values/colors.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#000000</color>
    <color name="obsidian_bg">#050507</color>
    <color name="carbon_card">#101217</color>
    <color name="titanium_border">#3E4656</color>
    <color name="titanium_bright">#E2E8F0</color>
    <color name="titanium_silver">#94A3B8</color>
</resources>
"""

FILES["app/src/main/res/values/themes.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.AxiomTitanium" parent="Theme.AppCompat.NoActionBar">
        <item name="android:windowBackground">@color/obsidian_bg</item>
        <item name="android:statusBarColor">@color/obsidian_bg</item>
        <item name="android:navigationBarColor">@color/obsidian_bg</item>
        <item name="android:colorAccent">@color/titanium_silver</item>
    </style>
</resources>
"""

FILES["app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
"""

FILES["app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
"""

FILES["app/src/main/res/drawable/ic_launcher_foreground.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path android:pathData="M0,0h108v108h-108z" android:fillColor="#000000" />

    <path android:pathData="M50,20 L56,20 L54,48 C53,55 46,60 36,66 L22,84 L31,54 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:startX="22" android:startY="84"
                android:endX="56" android:endY="20" android:type="linear">
                <item android:offset="0.0" android:color="#64748B"/>
                <item android:offset="0.5" android:color="#E2E8F0"/>
                <item android:offset="1.0" android:color="#FFFFFF"/>
            </gradient>
        </aapt:attr>
    </path>

    <path android:pathData="M50,20 L58,20 L86,84 L72,66 C62,60 55,55 54,48 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:startX="50" android:startY="20"
                android:endX="86" android:endY="84" android:type="linear">
                <item android:offset="0.0" android:color="#FFFFFF"/>
                <item android:offset="0.45" android:color="#CBD5E1"/>
                <item android:offset="1.0" android:color="#475569"/>
            </gradient>
        </aapt:attr>
    </path>

    <path android:pathData="M20,86 L36,86 L51,63 C48,57 42,56 34,62 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:startX="20" android:startY="86"
                android:endX="51" android:endY="58" android:type="linear">
                <item android:offset="0.0" android:color="#F8FAFC"/>
                <item android:offset="0.6" android:color="#94A3B8"/>
                <item android:offset="1.0" android:color="#334155"/>
            </gradient>
        </aapt:attr>
    </path>

    <path android:pathData="M88,86 L72,86 L57,63 C60,57 66,56 74,62 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:startX="57" android:startY="58"
                android:endX="88" android:endY="86" android:type="linear">
                <item android:offset="0.0" android:color="#334155"/>
                <item android:offset="0.4" android:color="#CBD5E1"/>
                <item android:offset="1.0" android:color="#F8FAFC"/>
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""

# =============================================================================
# 5. BRAIN: CALC.KT  (opraveno \\n → newline)
# =============================================================================
FILES["app/src/main/java/cz/axiom/offline/brain/Calc.kt"] = """package cz.axiom.offline.brain

import kotlin.math.pow
import kotlin.math.roundToLong

object Calc {

    fun eval(expr: String): Result<Double> = runCatching {
        val cleaned = expr.replace(",", ".").replace(" ", "")
        require(cleaned.isNotEmpty()) { "Prázdný výraz" }
        Parser(cleaned).parseExpression()
    }

    fun extractAndComputeAll(text: String): List<String> {
        val out = mutableListOf<String>()
        val lower = text.lowercase()

        val mathRegex = Regex(\"\"\"(\\d+(?:[.,]\\d+)?(?:\\s*[\\+\\-\\*/\\^]\\s*\\d+(?:[.,]\\d+)?)+)\"\"\")
        mathRegex.findAll(text).forEach { m ->
            eval(m.value.trim()).onSuccess { res ->
                out.add("STEP-0 MATH: ${m.value.trim()} = ${formatNum(res)}")
            }
        }

        val oddsMatch = Regex(\"\"\"kurz\\s*(\\d+(?:[.,]\\d+)?)\"\"\", RegexOption.IGNORE_CASE).find(text)
        val probMatch = Regex(\"\"\"(\\d+(?:[.,]\\d+)?)\\s*%\"\"\").find(text)
        if (oddsMatch != null && probMatch != null) {
            val odds = oddsMatch.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
            val p = (probMatch.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0) / 100.0
            if (odds > 1.0 && p in 0.01..0.99) {
                val fairOdds = 1.0 / p
                val evPct = (p * odds - 1.0) * 100.0
                val fullKelly = (((odds - 1.0) * p - (1.0 - p)) / (odds - 1.0)).coerceAtLeast(0.0) * 100.0
                val halfKelly = fullKelly / 2.0
                val status = if (evPct >= 3.0) "SILNÉ +EV (VSADIT)" else if (evPct > 0.0) "MÍRNÉ +EV" else "-EV (NEVSÁZET)"
                out.add(
                    "ASTRAEA +EV ENGINE: Kurz=$odds | Odhad=${formatNum(p * 100)}% | " +
                    "Férový kurz=${formatNum(fairOdds)} | EV=${formatNum(evPct)}% [$status] | " +
                    "Half-Kelly vklad=${formatNum(halfKelly)}% bankrollu"
                )
            }
        }

        val areaMatch = Regex(\"\"\"(\\d+(?:[.,]\\d+)?)\\s*m2\"\"\", RegexOption.IGNORE_CASE).find(text)
        if (areaMatch != null && (lower.contains("mikrocement") || lower.contains("stěrk") ||
            lower.contains("podlah") || lower.contains("vinyl"))) {
            val m2 = areaMatch.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
            if (m2 > 0) {
                val m2Waste = m2 * 1.10
                val nivelace = (m2 * 1.5 * 3.0) / 25.0
                out.add(
                    "STAVEBNÍ KALKULACE (${formatNum(m2)} m2 | s 10% prořezem ${formatNum(m2Waste)} m2):\\n" +
                    "  • Penetrace=${formatNum(m2 * 0.15)} kg, Base=${formatNum(m2 * 1.60)} kg, " +
                    "Fine=${formatNum(m2 * 0.80)} kg, 2K PU Lak=${formatNum(m2 * 0.22)} kg\\n" +
                    "  • Samonivelace (tl. 3 mm): ${formatNum(nivelace)} pytlů (25 kg)"
                )
            }
        }

        val jpyMatch = Regex(\"\"\"(\\d+(?:[.,]\\d+)?)\\s*(?:jpy|jenů|yen)\"\"\", RegexOption.IGNORE_CASE).find(text)
        if (jpyMatch != null) {
            val jpy = jpyMatch.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
            val czk = jpy * 0.155
            val dph = czk * 0.21
            out.add(
                "IMPORT JAPONSKO: ${formatNum(jpy)} JPY ≈ ${formatNum(czk)} Kč | DPH 21% = ${formatNum(dph)} Kč | " +
                "Koncová cena s dopravou ≈ ${formatNum(czk + dph + 450.0)} Kč (Clo TARIC 9504400000 = 0 %)"
            )
        }

        return out
    }

    fun formatNum(value: Double): String {
        val rounded = (value * 100.0).roundToLong() / 100.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    private class Parser(private val input: String) {
        private var pos = 0

        fun parseExpression(): Double {
            var x = parseTerm()
            while (pos < input.length) {
                when (input[pos]) {
                    '+' -> { pos++; x += parseTerm() }
                    '-' -> { pos++; x -= parseTerm() }
                    else -> return x
                }
            }
            return x
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (pos < input.length) {
                when (input[pos]) {
                    '*' -> { pos++; x *= parseFactor() }
                    '/' -> {
                        pos++
                        val d = parseFactor()
                        require(d != 0.0) { "Dělení nulou" }
                        x /= d
                    }
                    else -> return x
                }
            }
            return x
        }

        private fun parseFactor(): Double {
            if (pos < input.length && input[pos] == '+') { pos++; return parseFactor() }
            if (pos < input.length && input[pos] == '-') { pos++; return -parseFactor() }
            var x: Double
            val start = pos
            if (pos < input.length && input[pos] == '(') {
                pos++
                x = parseExpression()
                if (pos < input.length && input[pos] == ')') pos++
            } else {
                while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
                require(pos > start) { "Neočekávaný znak na pozici $start" }
                x = input.substring(start, pos).toDouble()
            }
            if (pos < input.length && input[pos] == '^') {
                pos++
                x = x.pow(parseFactor())
            }
            return x
        }
    }
}
"""

# =============================================================================
# 6. BRAIN: UNITS.KT
# =============================================================================
FILES["app/src/main/java/cz/axiom/offline/brain/Units.kt"] = """package cz.axiom.offline.brain

object Units {

    fun convert(value: Double, fromUnit: String, toUnit: String): Result<Double> = runCatching {
        val f = fromUnit.trim().lowercase()
        val t = toUnit.trim().lowercase()
        if (f == t) return@runCatching value

        if (f == "c" && t == "f") return@runCatching value * 9.0 / 5.0 + 32.0
        if (f == "f" && t == "c") return@runCatching (value - 32.0) * 5.0 / 9.0

        val length = mapOf("mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
            "in" to 0.0254, "ft" to 0.3048, "mi" to 1609.344)
        if (f in length && t in length) return@runCatching value * length.getValue(f) / length.getValue(t)

        val weight = mapOf("mg" to 0.001, "g" to 1.0, "kg" to 1000.0, "t" to 1_000_000.0,
            "lb" to 453.59237, "oz" to 28.34952)
        if (f in weight && t in weight) return@runCatching value * weight.getValue(f) / weight.getValue(t)

        val rates = mapOf(
            ("nm" to "lbft") to 0.737562, ("lbft" to "nm") to 1.355818,
            ("bar" to "psi") to 14.5038, ("psi" to "bar") to 0.0689476,
            ("eur" to "czk") to 25.30, ("czk" to "eur") to (1.0 / 25.30),
            ("usd" to "czk") to 23.40,
            ("jpy" to "czk") to 0.155, ("czk" to "jpy") to (1.0 / 0.155)
        )
        rates[f to t]?.let { return@runCatching value * it }

        throw IllegalArgumentException("Nepodporovaný převod: $fromUnit -> $toUnit")
    }

    fun scanAndConvertInText(text: String): List<String> {
        val regex = Regex(\"\"\"(\\d+(?:[.,]\\d+)?)\\s*(mm|cm|m|km|in|ft|mg|g|kg|t|lb|oz|nm|lbft|bar|psi|eur|usd|jpy|czk)\\s*(?:na|do|to|->|v)\\s*(mm|cm|m|km|in|ft|mg|g|kg|t|lb|oz|nm|lbft|bar|psi|eur|usd|jpy|czk)\"\"\", RegexOption.IGNORE_CASE)
        return regex.findAll(text).mapNotNull { m ->
            val v = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return@mapNotNull null
            convert(v, m.groupValues[2], m.groupValues[3]).getOrNull()?.let {
                "UNIT_CONVERT: $v ${m.groupValues[2].uppercase()} = ${Calc.formatNum(it)} ${m.groupValues[3].uppercase()}"
            }
        }.toList()
    }
}
"""

# =============================================================================
# 7. BRAIN: TEXT.KT (RANDY + FORENSIC)
# =============================================================================
FILES["app/src/main/java/cz/axiom/offline/brain/Text.kt"] = """package cz.axiom.offline.brain

enum class RandyDepthLevel(val code: String, val label: String) {
    L0_DIRECT("L0: DIRECT", "Okamžitý faktický výstup bez omáčky"),
    L1_EXPERT("L1: EXPERT", "Technické parametry a normy z databáze"),
    L2_DEEP("L2: DEEP", "Hloubková syntéza z prvních principů"),
    L3_STRATEGIC_EV("L3: STRATEGIC +EV", "Kvantitativní výpočet marže, ROI a +EV"),
    L4_NEMESIS_REDTEAM("L4: NEMESIS RED-TEAM", "Útočný audit, hledání slabin a rizik"),
    L5_FORENSIC("L5: FORENSIC", "Forenzní rekonstrukce stop a integrity dat"),
    L6_OMNISCIENT("L6: OMNISCIENT", "Multioborová syntéza s extrapolací dopadů")
}

object Text {

    fun normalize(input: String): String =
        input.trim().replace(Regex(\"\"\"\\s+\"\"\"), " ")

    fun detectDepthLevel(input: String): RandyDepthLevel {
        val t = input.lowercase()
        return when {
            listOf("forenz", "stopa", "hash", "integrit", "audit").any { it in t } -> RandyDepthLevel.L5_FORENSIC
            listOf("red team", "útok", "exploit", "slabina", "riziko", "hrozba").any { it in t } -> RandyDepthLevel.L4_NEMESIS_REDTEAM
            listOf("ev ", "+ev", "kelly", "marže", "roi", "kurz", "bankroll").any { it in t } -> RandyDepthLevel.L3_STRATEGIC_EV
            listOf("proč", "vysvětli", "syntéz", "princip", "derive").any { it in t } -> RandyDepthLevel.L2_DEEP
            listOf("parametr", "norma", "spec", "tabulka", "hodnota").any { it in t } -> RandyDepthLevel.L1_EXPERT
            else -> RandyDepthLevel.L0_DIRECT
        }
    }

    fun randyDirect(text: String): String =
        normalize(text).replace(Regex(\"\"\"(?i)\\b(prosím|děkuji|omlouvám se|bohužel|myslím, že)\\b\"\"\"), "").trim()

    fun forensicScan(input: String): List<String> {
        val patterns = mapOf(
            "URL" to Regex(\"\"\"https?://[\\w\\-./?%&=+#:]+\"\"\"),
            "EMAIL" to Regex(\"\"\"[\\w.+-]+@[\\w-]+\\.[\\w.-]+\"\"\"),
            "IPv4" to Regex(\"\"\"\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b\"\"\"),
            "SHA256" to Regex(\"\"\"\\b[a-fA-F0-9]{64}\\b\"\"\"),
            "IBAN" to Regex(\"\"\"\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b\"\"\"),
            "CASTKA_CZK" to Regex(\"\"\"(\\d[\\d\\s.,]*)\\s*(?:Kč|CZK)\"\"\", RegexOption.IGNORE_CASE),
            "TELEFON" to Regex(\"\"\"\\+?\\d{3}[\\s-]?\\d{3}[\\s-]?\\d{3}[\\s-]?\\d{3}\"\"\")
        )
        return patterns.flatMap { (name, rx) ->
            rx.findAll(input).map { "FORENSIC[$name]: ${it.value.take(80)}" }.toList()
        }
    }

    fun process(input: String): List<String> {
        if (input.isBlank()) return listOf("Zadej dotaz (např. '2+2*3', 'kurz 1.85, 55%', '10 m2 mikrocement', '1000 JPY').")
        val level = detectDepthLevel(input)
        val out = mutableListOf<String>()
        out.add("RANDY LEVEL: ${level.code} — ${level.label}")
        out.addAll(forensicScan(input))
        out.addAll(Calc.extractAndComputeAll(input))
        out.addAll(Units.scanAndConvertInText(input))
        val clean = randyDirect(input)
        if (clean.isNotBlank()) out.add("DIRECT: $clean")
        return out
    }
}
"""

# =============================================================================
# 8. MAIN ACTIVITY
# =============================================================================
FILES["app/src/main/java/cz/axiom/offline/MainActivity.kt"] = """package cz.axiom.offline

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cz.axiom.offline.brain.Text

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val input = findViewById<EditText>(R.id.input)
        val runBtn = findViewById<Button>(R.id.runBtn)
        val clearBtn = findViewById<Button>(R.id.clearBtn)
        val output = findViewById<TextView>(R.id.output)

        runBtn.setOnClickListener {
            val raw = input.text.toString()
            output.text = Text.process(raw).joinToString("\\n\\n")
        }

        clearBtn.setOnClickListener {
            input.setText("")
            output.text = ""
        }
    }
}
"""

FILES["app/src/main/res/layout/activity_main.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/obsidian_bg"
    android:padding="16dp">

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="AXIOM TITANIUM 5.5"
        android:textColor="@color/titanium_bright"
        android:textSize="20sp"
        android:textStyle="bold"
        android:paddingBottom="8dp" />

    <EditText
        android:id="@+id/input"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="Zadej dotaz…"
        android:textColor="@color/titanium_bright"
        android:textColorHint="@color/titanium_silver"
        android:background="@color/carbon_card"
        android:padding="12dp"
        android:inputType="textMultiLine"
        android:minLines="2" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:paddingTop="8dp"
        android:paddingBottom="8dp">

        <Button
            android:id="@+id/runBtn"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginEnd="4dp"
            android:text="SPUSTIT"
            android:textColor="@color/titanium_bright" />

        <Button
            android:id="@+id/clearBtn"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="4dp"
            android:text="SMAZAT"
            android:textColor="@color/titanium_silver" />
    </LinearLayout>

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:background="@color/carbon_card">

        <TextView
            android:id="@+id/output"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:padding="12dp"
            android:textColor="@color/titanium_bright"
            android:textSize="14sp"
            android:fontFamily="monospace" />
    </ScrollView>
</LinearLayout>
"""

# =============================================================================
# 9. UNIT TESTY
# =============================================================================
FILES["app/src/test/java/cz/axiom/offline/CalcTest.kt"] = """package cz.axiom.offline

import cz.axiom.offline.brain.Calc
import cz.axiom.offline.brain.RandyDepthLevel
import cz.axiom.offline.brain.Text
import cz.axiom.offline.brain.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcTest {

    @Test fun math_precedence() {
        assertEquals(10.0, Calc.eval("2+2*3+2").getOrThrow(), 1e-9)
    }

    @Test fun math_power_right_assoc() {
        assertEquals(512.0, Calc.eval("2^3^2").getOrThrow(), 1e-9)
    }

    @Test fun unit_cm_to_m() {
        assertEquals(1.5, Units.convert(150.0, "cm", "m").getOrThrow(), 1e-9)
    }

    @Test fun ev_engine_positive() {
        val out = Calc.extractAndComputeAll("kurz 1.85, pravděpodobnost 60%")
        assertTrue(out.any { it.contains("+EV ENGINE") })
    }

    @Test fun randy_depth_l3() {
        assertEquals(RandyDepthLevel.L3_STRATEGIC_EV, Text.detectDepthLevel("sport +EV kelly kurz"))
    }

    @Test fun forensic_sha256() {
        val hash = "a".repeat(64)
        assertTrue(Text.forensicScan("hash: $hash").any { it.contains("SHA256") })
    }
}
"""

# =============================================================================
# 10. ZÁPIS NA DISK
# =============================================================================
def write_project():
    if PROJECT_DIR.exists():
        shutil.rmtree(PROJECT_DIR)
    PROJECT_DIR.mkdir(parents=True, exist_ok=True)

    for rel, content in FILES.items():
        target = PROJECT_DIR / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    # Volitelně: HD PNG ikona z 4482.png
    if USER_LOGO.exists():
        (PROJECT_DIR / "app/src/main/res/drawable").mkdir(parents=True, exist_ok=True)
        shutil.copy(USER_LOGO, PROJECT_DIR / "app/src/main/res/drawable/logo_titanium.png")
        (PROJECT_DIR / "app/src/main/res/mipmap-xxxhdpi").mkdir(parents=True, exist_ok=True)
        shutil.copy(USER_LOGO, PROJECT_DIR / "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
        print(f"✓ Vložena HD ikona: {USER_LOGO}")
    else:
        print(f"! Soubor {USER_LOGO} nenalezen — použita vektorová ikona.")

    # Workflow navíc do aktuální složky (pro snadný upload)
    WORKFLOW_FILE.parent.mkdir(parents=True, exist_ok=True)
    if not WORKFLOW_FILE.exists():
        WORKFLOW_FILE.write_text(FILES[".github/workflows/build-apk.yml"], encoding="utf-8")
        print(f"✓ Workflow: {WORKFLOW_FILE}")


def make_zip():
    if ZIP_NAME.exists():
        ZIP_NAME.unlink()
    with zipfile.ZipFile(ZIP_NAME, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _, files in os.walk(PROJECT_DIR):
            for f in files:
                full = Path(root) / f
                zf.write(full, full.relative_to(PROJECT_DIR))
    print(f"✓ ZIP archiv: {ZIP_NAME} ({ZIP_NAME.stat().st_size // 1024} KB)")


def main():
    print("=" * 60)
    print(" AXIOM 3.0 × ASTRAEA 5.0 — OBSIDIAN & TITANIUM BUILDER")
    print("=" * 60)
    write_project()
    make_zip()
    print("-" * 60)
    print(f"✓ Projekt: {PROJECT_DIR.resolve()}")
    print("→ Nahraj na GitHub:")
    print("  1) build_axiom_titanium.py")
    print("  2) .github/workflows/build-apk.yml")
    print("  3) Actions → Run workflow")
    print("=" * 60)


if __name__ == "__main__":
    main()