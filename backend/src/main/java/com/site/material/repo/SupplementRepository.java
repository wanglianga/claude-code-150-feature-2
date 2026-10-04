package com.site.material.repo;

import com.site.material.domain.MaterialSupplement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface SupplementRepository extends JpaRepository<MaterialSupplement, Long> {

    @Query("select distinct s from MaterialSupplement s left join fetch s.items order by s.applyTime desc")
    List<MaterialSupplement> findAllWithItems();

    @Query("select distinct s from MaterialSupplement s left join fetch s.items where s.id = :id")
    java.util.Optional<MaterialSupplement> findByIdWithItems(Long id);

    @Query("select distinct s from MaterialSupplement s left join fetch s.items " +
            "where s.crewId = :crewId and s.applyTime >= :from and s.applyTime < :to")
    List<MaterialSupplement> findByCrewBetween(Long crewId, LocalDateTime from, LocalDateTime to);
}
