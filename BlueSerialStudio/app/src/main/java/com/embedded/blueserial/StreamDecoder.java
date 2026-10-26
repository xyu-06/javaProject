package com.embedded.blueserial;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Handles UTF-8 code points split across Bluetooth RFCOMM reads. */
public final class StreamDecoder {
    private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private ByteBuffer pending = ByteBuffer.allocate(8192);
    public String decode(byte[] bytes) {
        if (pending.remaining() < bytes.length) {
            ByteBuffer expanded = ByteBuffer.allocate(pending.position() + bytes.length + 1024);
            pending.flip(); expanded.put(pending); pending = expanded;
        }
        pending.put(bytes);
        pending.flip();
        CharBuffer chars = CharBuffer.allocate(pending.remaining() * 2 + 8);
        decoder.decode(pending, chars, false);
        pending.compact();
        chars.flip();
        return chars.toString();
    }
    public void reset() { decoder.reset(); pending.clear(); }
}
