package entities;


import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Transient;
import java.io.Serializable;
import java.util.List;

@Entity
@Getter
@Setter
public class DocumentType implements Serializable {

    @Id
    private Long id;
    @Column
    private String name;
    @Column(unique = true)
    private String code;
    @Column
    private Boolean active;
    @Transient
    private List<DocumentMainSubject> mainSubjects;
}
