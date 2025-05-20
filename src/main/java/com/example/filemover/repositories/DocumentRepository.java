package com.example.filemover.repositories;

import com.example.filemover.entities.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentRepository extends JpaRepository<Document,Long> {
    Document findByUcmDocID(String ucmDocId);
}
