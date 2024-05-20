package com.saayam.k8;

/**
 * Supported environments by this pulumi implementation.
 * Add more environments here if needed, but you will also
 * need a corresponding spring profile that matches the
 * environment name.
 */
public enum Environment {
  qa, sandbox, production
}
