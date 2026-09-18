package org.minechestplate.mcpskins.api;

/** What caused a skin to be taken away. */
public enum RevokeSource {
    /** {@code /mcpskins take skin} or {@code /mcpskins take skins}. */
    COMMAND,
    /** {@link MCPSkinsAPI#revoke}, so another mod or a script. */
    API
}
