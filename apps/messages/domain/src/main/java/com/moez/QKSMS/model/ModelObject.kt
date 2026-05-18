/*
 * Copyright (C) 2026
 *
 * This file is part of Messages.
 */
package dev.octoshrimpy.quik.model

import java.io.Serializable

open class ModelObject : Serializable {
    val isLoaded: Boolean get() = true
    val isValid: Boolean get() = true
}

