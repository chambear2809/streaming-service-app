package io.github.marianciuc.streamingservice.subscription.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "resolutions")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "subscriptionSet")
public class Resolution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(nullable = false, name = "name", unique = true)
    @EqualsAndHashCode.Include
    private String name;

    @Column(nullable = false, name = "description")
    private String description;

    @ManyToMany(mappedBy = "resolutions")
    private Set<Subscription> subscriptionSet;
}
