package com.studentforum.service;

import com.studentforum.repository.StudyPreferencesRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StudyPreferencesService {
    private final StudyPreferencesRepository repository;

    public StudyPreferencesService(StudyPreferencesRepository repository) {
        this.repository = repository;
    }

    /** 按可见性取用户的"学习小组"候选，供打卡目标/自习室关联小组时选择。 */
    public List<Map<String, Object>> myGroups(long userId) {
        return repository.myGroups(userId);
    }

    /** 默认学习科目候选：用户资料里填的偏好 + 学习小组里出现过的科目。 */
    public List<String> subjects(long userId) {
        List<String> subjects = new ArrayList<>();
        Map<String, Object> profile = repository.subjectPreference(userId);
        if (profile != null && profile.get("subject_preference") != null) {
            for (String part : String.valueOf(profile.get("subject_preference")).split("[,，、/]")) {
                String value = part.trim();
                if (!value.isEmpty() && !subjects.contains(value)) subjects.add(value);
            }
        }
        for (Map<String, Object> group : myGroups(userId)) {
            String subject = group.get("subject") == null ? "" : String.valueOf(group.get("subject")).trim();
            if (!subject.isEmpty() && !subjects.contains(subject)) subjects.add(subject);
        }
        return subjects.size() > 20 ? subjects.subList(0, 20) : subjects;
    }

}
