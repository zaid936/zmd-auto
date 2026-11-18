package com.andrerinas.openheadunit.zmd

import androidx.core.content.FileProvider

/**
 * The engine's file provider under its own class name: inside ZMD Car, ZMD Car already declares
 * androidx's FileProvider (another authority), and one manifest can't list the same provider
 * class twice. The engine finds it by authority ("<package>.fileprovider"), so nothing else changes.
 */
class EngineFileProvider : FileProvider()
