package com.example.filemover.entities;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.io.Serializable;

@Entity
@Getter
@Setter
public class DocumentSubSubject implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "DOC_SUB_SUBJECT_SEQ")
    @SequenceGenerator(sequenceName = "DOC_SUB_SUBJECT_SEQ", allocationSize = 1, name = "DOC_SUB_SUBJECT_SEQ")
    private Long id;
    @Column
    private String name;
    @Column(unique = true)
    private String code;
    @Column
    private Boolean active;
    @ManyToOne
    private DocumentMainSubject mainSubject;
    @Column
    private Integer years;

}
