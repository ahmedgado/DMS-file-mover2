package com.example.filemover.repositories;

import com.example.filemover.entities.Folder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FolderRepository extends JpaRepository<Folder,Long> {

    Folder findByUcmGUID (String GUID);
    Optional<Folder> findByUcmFullUrl (String ucmFullUrl);
    Optional<Folder> findByFolderNameAndUcmParentFolderGUID (String folderName, String parentId);
}
