(() => {
  "use strict";
  const host = document.getElementById("bot");
  let avatar = null;

  window.HitchTalkAvatar = {
    update(options) {
      try {
        const validation = HitchTalkAvatarRuntime.validateAvatar(options.definition);
        if (!validation.ok) throw new Error("Invalid local avatar definition");
        const settings = {
          ...options,
          definition: validation.value,
          renderer: "canvas",
          label: "",
          lookAt: options.still ? { x: 0, y: 0 } : undefined
        };
        if (avatar) avatar.update(settings);
        else avatar = HitchTalkAvatarRuntime.mountAvatar(host, settings);
        host.dataset.avatarId = validation.value.id;
        host.dataset.mood = settings.mood;
        host.dataset.still = String(settings.still);
        host.dataset.renderState = "ready";
      } catch (_) {
        this.destroy();
        host.dataset.renderState = "error";
      }
    },
    destroy() {
      if (avatar) avatar.destroy();
      avatar = null;
      host.replaceChildren();
      host.dataset.renderState = "destroyed";
    }
  };
})();
