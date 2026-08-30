package net.minecraft.network.chat;

import java.time.Duration;
import java.util.UUID;
import net.minecraft.network.chat.RemoteChatSession.Data;
import net.minecraft.network.chat.SignedMessageChain.Decoder;
import net.minecraft.network.chat.SignedMessageValidator.KeyBased;
import net.minecraft.world.entity.player.ProfilePublicKey;

public record RemoteChatSession(UUID sessionId, ProfilePublicKey profilePublicKey) {
   public SignedMessageValidator createMessageValidator(Duration gracePeriod) {
      return new KeyBased(this.profilePublicKey.createSignatureValidator(), () -> this.profilePublicKey.data().hasExpired(gracePeriod));
   }

   public Decoder createMessageDecoder(UUID profileId) {
      return new SignedMessageChain(profileId, this.sessionId).decoder(this.profilePublicKey);
   }

   public Data asData() {
      return new Data(this.sessionId, this.profilePublicKey.data());
   }

   public boolean hasExpired() {
      return this.profilePublicKey.data().hasExpired();
   }
}
