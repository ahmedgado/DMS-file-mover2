package com.example.filemover.entities;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Set;

@Entity
@Getter
@Setter
@Data
public class Folder  implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "folder_seq")
    @SequenceGenerator(sequenceName = "folder_seq", allocationSize = 1, name = "folder_seq")
    private Long id;

    @Column
    private String folderName;

    @Column
    private String ucmFullUrl;

    @Column
    private String ucmParentFolderGUID;

    @Column
    private String ucmParentFolderName;

    @Column
    private String ucmGUID;

    @JoinColumn
    @ManyToOne
    private Folder parentFolder;

    @Transient
    private Long numOfDocument;

    @Transient
    private Set<Folder> children;

    @Column
    private String folderSubjectType;
    @Column
    private Long subjectTypeId;

}

