package com.chatchat.api.datascience.python;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** One default per authenticated tenant/user, independent of mutable image tags. */
@Getter @Setter @Entity
@Table(name = "ds_python_environment_preference", uniqueConstraints =
    @UniqueConstraint(name = "uk_python_environment_preference_owner", columnNames = {"tenant_id", "owner_id"}))
public class PythonEnvironmentPreference {
    @Id @Column(length = 64) private String id;
    @Column(name = "tenant_id", length = 64, nullable = false) private String tenantId;
    @Column(name = "owner_id", length = 64, nullable = false) private String ownerId;
    @Column(name = "asset_id", length = 64, nullable = false) private String assetId;
    @Version @Column(name = "revision", nullable = false) private long revision;
}
