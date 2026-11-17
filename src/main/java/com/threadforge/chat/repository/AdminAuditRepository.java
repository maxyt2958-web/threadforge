/*
 * Copyright 2026 ThreadForge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.threadforge.chat.repository;

import com.threadforge.chat.domain.AdminAudit;
import com.threadforge.chat.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only. There is deliberately no delete or update method beyond what JpaRepository forces. */
public interface AdminAuditRepository extends JpaRepository<AdminAudit, Long> {

    /** Newest first, with actor and target fetched: the admin list renders both per row. */
    @EntityGraph(attributePaths = {"actor", "targetUser"})
    Page<AdminAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"actor", "targetUser"})
    Page<AdminAudit> findByTargetUserOrderByCreatedAtDesc(User targetUser, Pageable pageable);
}
