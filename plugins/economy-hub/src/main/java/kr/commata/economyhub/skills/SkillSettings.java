package kr.commata.economyhub.skills;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/** config.yml skills (ECONOMY.md 15장). 서버의 config.yml 에 없으면 JAR 기본값, 그것도 없으면 코드 기본값. */
public record SkillSettings(boolean enabled, Map<Skill, Integer> unlockLevels, Set<Skill> disabled, int caveMaxY,
                            int leafRadius, int detectRadius, int detectCooldownSeconds, int glowSeconds,
                            Set<Material> trash) {

    public static SkillSettings read(FileConfiguration config, Logger log) {
        Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
        for (Skill skill : Skill.values()) {
            levels.put(skill, Math.max(1, config.getInt("skills.unlock-levels." + skill.id(), skill.defaultUnlock())));
        }
        Set<Skill> disabled = EnumSet.noneOf(Skill.class);
        for (String id : config.getStringList("skills.disabled")) {
            Skill match = null;
            for (Skill skill : Skill.values()) {
                if (skill.id().equalsIgnoreCase(id.trim())) {
                    match = skill;
                }
            }
            if (match == null) {
                log.warning("skills.disabled: 없는 스킬 " + id);
            } else {
                disabled.add(match);
            }
        }
        Set<Material> trash = EnumSet.noneOf(Material.class);
        for (String name : config.getStringList("skills.fisherman-trash")) {
            Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (material == null) {
                log.warning("skills.fisherman-trash: 없는 아이템 " + name);
            } else if (material == Material.FISHING_ROD) {
                log.warning("skills.fisherman-trash: 낚싯대는 보물일 수 있어 버리지 않습니다");
            } else {
                trash.add(material);
            }
        }
        return new SkillSettings(config.getBoolean("skills.enabled", true), Map.copyOf(levels), Set.copyOf(disabled),
                config.getInt("skills.cave-vision.max-y", 50),
                Math.clamp(config.getInt("skills.leaf-decay.radius", 6), 2, 8),
                Math.clamp(config.getInt("skills.hunter-detect.radius", 24), 4, 64),
                Math.max(1, config.getInt("skills.hunter-detect.cooldown-seconds", 12)),
                Math.clamp(config.getInt("skills.hunter-detect.glow-seconds", 6), 1, 30),
                Set.copyOf(trash));
    }

    public int unlock(Skill skill) {
        return unlockLevels.getOrDefault(skill, skill.defaultUnlock());
    }

    /** 서버에서 켜 둔 스킬인지 (전체 스위치 + 개별 끄기 목록) */
    public boolean available(Skill skill) {
        return enabled && !disabled.contains(skill);
    }
}
